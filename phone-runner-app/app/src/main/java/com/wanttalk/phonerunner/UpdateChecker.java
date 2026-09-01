package com.wanttalk.phonerunner;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class UpdateChecker {
    private static final String UPDATE_URL =
        "https://raw.githubusercontent.com/wanttalk/TEST/main/update.json";
    private static final String APK_BASE_URL =
        "https://github.com/wanttalk/TEST/releases/download/";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String PREFS = "phone_runner_updates";
    private static final String PENDING_JSON = "pending_update";
    private static final String DOWNLOAD_ID = "download_id";
    private static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private UpdateChecker() {}

    static void check(Activity activity, boolean manual) {
        SharedPreferences prefs = prefs(activity);
        long now = System.currentTimeMillis();
        long lastCheck = prefs.getLong("last_check", 0L);
        if (!manual && now - lastCheck < CHECK_INTERVAL_MS) return;
        prefs.edit().putLong("last_check", now).apply();

        new Thread(() -> {
            try {
                UpdateInfo info = fetchInfo();
                if (info.versionCode <= BuildConfig.VERSION_CODE) {
                    if (manual) toast(activity, "目前已是最新版本");
                    return;
                }
                activity.runOnUiThread(() -> prepareInstall(activity, info));
            } catch (Exception e) {
                if (manual) toast(activity, "檢查更新失敗：" + e.getMessage());
            }
        }, "phone-runner-update-check").start();
    }

    static void resumePendingInstall(Activity activity) {
        String json = prefs(activity).getString(PENDING_JSON, "");
        if (json.trim().isEmpty()) return;

        try {
            UpdateInfo info = UpdateInfo.fromJson(new JSONObject(json));
            if (info.versionCode <= BuildConfig.VERSION_CODE) {
                clearDownloadState(activity);
                return;
            }
            if (!canInstallPackages(activity)
                || prefs(activity).getLong(DOWNLOAD_ID, -1L) != -1L) {
                return;
            }
            enqueue(activity, info);
        } catch (Exception e) {
            clearDownloadState(activity);
        }
    }

    static String pendingJson(Context context) {
        return prefs(context).getString(PENDING_JSON, "");
    }

    static long downloadId(Context context) {
        return prefs(context).getLong(DOWNLOAD_ID, -1L);
    }

    static void clearDownloadState(Context context) {
        prefs(context).edit()
            .remove(PENDING_JSON)
            .remove(DOWNLOAD_ID)
            .apply();
    }

    static boolean verifySha256(Context context, Uri uri, String expected) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return false;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            return toHex(digest.digest()).equalsIgnoreCase(expected);
        } catch (Exception e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static UpdateInfo fetchInfo() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod("GET");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + connection.getResponseCode());
            }
            StringBuilder json = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(),
                StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) json.append(line);
            }
            return UpdateInfo.fromJson(new JSONObject(json.toString()));
        } finally {
            connection.disconnect();
        }
    }

    private static void prepareInstall(Activity activity, UpdateInfo info) {
        prefs(activity).edit().putString(PENDING_JSON, info.toJson()).apply();
        if (!canInstallPackages(activity)) {
            toast(activity, "找到 Phone Runner " + info.versionName
                + "；請允許本 App 安裝更新，返回後會自動下載");
            try {
                activity.startActivity(new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())
                ));
            } catch (Exception e) {
                toast(activity, "請在系統設定允許 Phone Runner 安裝未知來源");
            }
            return;
        }
        enqueue(activity, info);
    }

    private static boolean canInstallPackages(Activity activity) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
            || activity.getPackageManager().canRequestPackageInstalls();
    }

    private static void enqueue(Activity activity, UpdateInfo info) {
        if (prefs(activity).getLong(DOWNLOAD_ID, -1L) != -1L) return;
        DownloadManager manager =
            (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            toast(activity, "系統下載服務不可用");
            return;
        }

        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(info.apkUrl));
            request.setTitle("Phone Runner 更新 " + info.versionName);
            request.setDescription("下載完成後會開啟 Android 一次確認安裝");
            request.setMimeType(APK_MIME);
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            request.setDestinationInExternalFilesDir(
                activity,
                Environment.DIRECTORY_DOWNLOADS,
                "phone-runner-update.apk"
            );
            long id = manager.enqueue(request);
            prefs(activity).edit()
                .putString(PENDING_JSON, info.toJson())
                .putLong(DOWNLOAD_ID, id)
                .apply();
            toast(activity, "已開始下載 Phone Runner " + info.versionName);
        } catch (Exception e) {
            toast(activity, "開始下載失敗：" + e.getMessage());
        }
    }

    private static void toast(Activity activity, String text) {
        activity.runOnUiThread(() -> Toast.makeText(activity, text, Toast.LENGTH_LONG).show());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value));
        return result.toString();
    }

    static final class UpdateInfo {
        final int versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;

        private UpdateInfo(int versionCode, String versionName, String apkUrl, String sha256) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
        }

        static UpdateInfo fromJson(JSONObject object) throws Exception {
            int versionCode = object.getInt("version_code");
            String versionName = object.getString("version_name").trim();
            String apkUrl = object.getString("apk_url").trim();
            String sha256 = object.getString("sha256").trim().toLowerCase(Locale.US);
            if (versionCode < 1 || versionName.isEmpty()
                || !apkUrl.startsWith(APK_BASE_URL)
                || !apkUrl.endsWith("/phone-runner.apk")
                || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("更新資訊格式無效");
            }
            return new UpdateInfo(versionCode, versionName, apkUrl, sha256);
        }

        String toJson() {
            try {
                return new JSONObject()
                    .put("version_code", versionCode)
                    .put("version_name", versionName)
                    .put("apk_url", apkUrl)
                    .put("sha256", sha256)
                    .toString();
            } catch (Exception e) {
                throw new IllegalStateException("更新資訊無法保存", e);
            }
        }
    }
}
