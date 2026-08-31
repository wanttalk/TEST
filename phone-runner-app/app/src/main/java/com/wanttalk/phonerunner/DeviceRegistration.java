package com.wanttalk.phonerunner;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DeviceRegistration {
    private static final String PREFS = "phone_runner_pairing";
    private static final String KEY_WORKER_URL = "worker_url";
    private static final String KEY_PAIRING_TOKEN = "pairing_token";
    private static final String KEY_DEVICE_TOKEN = "device_token";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private DeviceRegistration() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String getWorkerUrl(Context context) {
        return prefs(context).getString(KEY_WORKER_URL, "");
    }

    static String getPairingToken(Context context) {
        return prefs(context).getString(KEY_PAIRING_TOKEN, "");
    }

    private static String getDeviceToken(Context context) {
        return prefs(context).getString(KEY_DEVICE_TOKEN, "");
    }

    private static String getAuthToken(Context context) {
        String deviceToken = getDeviceToken(context);
        return deviceToken.isEmpty() ? getPairingToken(context) : deviceToken;
    }

    static boolean isConfigured(Context context) {
        return !getWorkerUrl(context).isEmpty() && !getAuthToken(context).isEmpty();
    }

    static boolean isPaired(Context context) {
        return !getWorkerUrl(context).isEmpty() && !getDeviceToken(context).isEmpty();
    }

    static void saveConfiguration(Context context, String workerUrl, String pairingToken) {
        String normalizedUrl = normalizeWorkerUrl(workerUrl);
        String normalizedToken = pairingToken == null ? "" : pairingToken.trim();
        if (normalizedUrl.isEmpty()) {
            throw new IllegalArgumentException("Worker URL 必須是 https:// 網址");
        }
        if (normalizedToken.length() < 20) {
            throw new IllegalArgumentException("一次性配對碼格式不正確");
        }

        SharedPreferences p = prefs(context);
        String previousUrl = p.getString(KEY_WORKER_URL, "");
        SharedPreferences.Editor edit = p.edit()
            .putString(KEY_WORKER_URL, normalizedUrl)
            .putString(KEY_PAIRING_TOKEN, normalizedToken);
        if (!normalizedUrl.equals(previousUrl)) {
            edit.remove(KEY_DEVICE_TOKEN);
        }
        edit.apply();
    }

    static void registerCurrentToken(Context context) {
        String fcmToken = RunnerState.getToken(context);
        if (!fcmToken.trim().isEmpty()) {
            register(context, fcmToken);
        }
    }

    static void register(Context context, String fcmToken) {
        Context appContext = context.getApplicationContext();
        String workerUrl = getWorkerUrl(appContext);
        String authToken = getAuthToken(appContext);
        if (workerUrl.isEmpty() || authToken.isEmpty() || fcmToken == null || fcmToken.trim().isEmpty()) {
            RunnerState.recordRegistration(appContext, "NOT_CONFIGURED");
            return;
        }

        String deviceId = getOrCreateDeviceId(appContext);
        EXECUTOR.execute(() -> doRegister(
            appContext,
            workerUrl,
            authToken,
            fcmToken.trim(),
            deviceId
        ));
    }

    static void reportPhase(Context context, String phase, String requestId, String priority) {
        report(context, phase, requestId, null, null, null, priority);
    }

    static void reportResult(Context context, String requestId, int err, int exitCode) {
        report(
            context,
            "result",
            requestId,
            err == Activity.RESULT_OK && exitCode == 0,
            err,
            exitCode,
            ""
        );
    }

    private static void report(
        Context context,
        String phase,
        String requestId,
        Boolean ok,
        Integer err,
        Integer exitCode,
        String priority
    ) {
        Context appContext = context.getApplicationContext();
        String workerUrl = getWorkerUrl(appContext);
        String deviceToken = getDeviceToken(appContext);
        if (workerUrl.isEmpty() || deviceToken.isEmpty()) return;

        String deviceId = getOrCreateDeviceId(appContext);
        String safePhase = phase == null ? "" : phase.trim();
        String safeRequestId = requestId == null ? "" : requestId.trim();
        String safePriority = priority == null ? "" : priority.trim();

        EXECUTOR.execute(() -> doReport(
            workerUrl,
            deviceToken,
            deviceId,
            safePhase,
            safeRequestId,
            ok,
            err,
            exitCode,
            safePriority
        ));
    }

    private static void doRegister(
        Context context,
        String workerUrl,
        String authToken,
        String fcmToken,
        String deviceId
    ) {
        String body = "{"
            + "\"token\":\"" + jsonEscape(fcmToken) + "\","
            + "\"device_id\":\"" + jsonEscape(deviceId) + "\","
            + "\"model\":\"" + jsonEscape(android.os.Build.MODEL) + "\""
            + "}";

        try {
            HttpResult result = postJson(workerUrl + "/register", authToken, body);
            if (result.status >= 200 && result.status < 300) {
                String issued = "";
                try {
                    issued = new JSONObject(result.body).optString("device_token", "").trim();
                } catch (Exception ignored) {}
                if (issued.length() >= 20) {
                    prefs(context).edit()
                        .putString(KEY_DEVICE_TOKEN, issued)
                        .remove(KEY_PAIRING_TOKEN)
                        .apply();
                }
                RunnerState.recordRegistration(context, "REGISTERED");
            } else {
                RunnerState.recordRegistration(context, "HTTP_" + result.status);
            }
        } catch (Exception e) {
            RunnerState.recordRegistration(
                context,
                "FAILED: " + e.getClass().getSimpleName() + ": " + safeMessage(e)
            );
        }
    }

    private static void doReport(
        String workerUrl,
        String deviceToken,
        String deviceId,
        String phase,
        String requestId,
        Boolean ok,
        Integer err,
        Integer exitCode,
        String priority
    ) {
        StringBuilder body = new StringBuilder();
        body.append('{')
            .append("\"device_id\":\"").append(jsonEscape(deviceId)).append("\",")
            .append("\"phase\":\"").append(jsonEscape(phase)).append("\",")
            .append("\"request_id\":\"").append(jsonEscape(requestId)).append("\",")
            .append("\"priority\":\"").append(jsonEscape(priority)).append("\"");
        if (ok != null) body.append(",\"ok\":").append(ok.booleanValue());
        if (err != null) body.append(",\"err\":").append(err.intValue());
        if (exitCode != null) body.append(",\"exit_code\":").append(exitCode.intValue());
        body.append('}');

        try {
            postJson(workerUrl + "/report", deviceToken, body.toString());
        } catch (Exception ignored) {
        }
    }

    private static HttpResult postJson(String endpoint, String bearerToken, String body) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }

            int status = connection.getResponseCode();
            String responseBody = readSmallBody(
                status >= 400 ? connection.getErrorStream() : connection.getInputStream()
            );
            return new HttpResult(status, responseBody);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String normalizeWorkerUrl(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (!trimmed.startsWith("https://")) return "";
        return trimmed;
    }

    private static String getOrCreateDeviceId(Context context) {
        SharedPreferences p = prefs(context);
        String current = p.getString("device_id", "");
        if (!current.isEmpty()) return current;
        String created = UUID.randomUUID().toString();
        p.edit().putString("device_id", created).apply();
        return created;
    }

    private static String readSmallBody(InputStream stream) {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && result.length() < 1000) {
                if (result.length() > 0) result.append(' ');
                result.append(line);
            }
            return result.length() <= 1000 ? result.toString() : result.substring(0, 1000);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String jsonEscape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static String safeMessage(Exception e) {
        String value = e.getMessage();
        if (value == null) return "unknown";
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    private static final class HttpResult {
        final int status;
        final String body;

        HttpResult(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }
}
