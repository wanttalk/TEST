package com.wanttalk.phonerunner;

import android.content.Context;
import android.content.SharedPreferences;

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
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private DeviceRegistration() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String getWorkerUrl(Context context) {
        return prefs(context).getString("worker_url", "");
    }

    static String getRegistrationToken(Context context) {
        return prefs(context).getString("registration_token", "");
    }

    static boolean isConfigured(Context context) {
        return !getWorkerUrl(context).isEmpty() && !getRegistrationToken(context).isEmpty();
    }

    static void saveConfiguration(Context context, String workerUrl, String registrationToken) {
        String normalizedUrl = normalizeWorkerUrl(workerUrl);
        String normalizedToken = registrationToken == null ? "" : registrationToken.trim();
        if (normalizedUrl.isEmpty()) {
            throw new IllegalArgumentException("Worker URL 必須是 https:// 網址");
        }
        if (normalizedToken.length() < 20) {
            throw new IllegalArgumentException("配對密鑰格式不正確");
        }
        prefs(context).edit()
            .putString("worker_url", normalizedUrl)
            .putString("registration_token", normalizedToken)
            .apply();
    }

    static void registerCurrentToken(Context context) {
        String token = RunnerState.getToken(context);
        if (!token.trim().isEmpty()) {
            register(context, token);
        }
    }

    static void register(Context context, String fcmToken) {
        Context appContext = context.getApplicationContext();
        String workerUrl = getWorkerUrl(appContext);
        String registrationToken = getRegistrationToken(appContext);
        if (workerUrl.isEmpty() || registrationToken.isEmpty() || fcmToken == null || fcmToken.trim().isEmpty()) {
            RunnerState.recordRegistration(appContext, "NOT_CONFIGURED");
            return;
        }

        String deviceId = getOrCreateDeviceId(appContext);
        EXECUTOR.execute(() -> doRegister(
            appContext,
            workerUrl,
            registrationToken,
            fcmToken.trim(),
            deviceId
        ));
    }

    static void reportPhase(
        Context context,
        String phase,
        String requestId,
        String priority
    ) {
        report(context, phase, requestId, null, null, null, priority);
    }

    static void reportResult(
        Context context,
        String requestId,
        int err,
        int exitCode
    ) {
        report(context, "result", requestId, err == 0 && exitCode == 0, err, exitCode, "");
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
        String registrationToken = getRegistrationToken(appContext);
        if (workerUrl.isEmpty() || registrationToken.isEmpty()) return;

        String deviceId = getOrCreateDeviceId(appContext);
        String safePhase = phase == null ? "" : phase.trim();
        String safeRequestId = requestId == null ? "" : requestId.trim();
        String safePriority = priority == null ? "" : priority.trim();

        EXECUTOR.execute(() -> doReport(
            workerUrl,
            registrationToken,
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
        String registrationToken,
        String fcmToken,
        String deviceId
    ) {
        String body = "{"
            + "\"token\":\"" + jsonEscape(fcmToken) + "\","
            + "\"device_id\":\"" + jsonEscape(deviceId) + "\","
            + "\"model\":\"" + jsonEscape(android.os.Build.MODEL) + "\""
            + "}";

        try {
            int status = postJson(workerUrl + "/register", registrationToken, body);
            if (status >= 200 && status < 300) {
                RunnerState.recordRegistration(context, "REGISTERED");
            } else {
                RunnerState.recordRegistration(context, "HTTP_" + status);
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
        String registrationToken,
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
            postJson(workerUrl + "/report", registrationToken, body.toString());
        } catch (Exception ignored) {
        }
    }

    private static int postJson(String endpoint, String bearerToken, String body) throws Exception {
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
            readSmallBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            return status;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String normalizeWorkerUrl(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
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
            while ((line = reader.readLine()) != null && result.length() < 500) {
                if (result.length() > 0) result.append(' ');
                result.append(line);
            }
            return result.length() <= 500 ? result.toString() : result.substring(0, 500);
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
}
