package com.wanttalk.phonerunner;

import android.content.BroadcastReceiver;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONObject;

public final class UpdateDownloadReceiver extends BroadcastReceiver {
    private static final String APK_MIME =
        "application/vnd.android.package-archive";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;

        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        if (id == -1L || id != UpdateChecker.downloadId(context)) return;

        DownloadManager manager =
            (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            fail(context, "系統下載服務不可用");
            return;
        }

        if (!successful(manager, id)) {
            fail(context, "APK 下載失敗");
            return;
        }

        Uri uri = manager.getUriForDownloadedFile(id);
        String json = UpdateChecker.pendingJson(context);
        try {
            UpdateChecker.UpdateInfo info =
                UpdateChecker.UpdateInfo.fromJson(new JSONObject(json));
            if (uri == null || !UpdateChecker.verifySha256(context, uri, info.sha256)) {
                manager.remove(id);
                fail(context, "APK 雜湊驗證失敗");
                return;
            }

            UpdateChecker.clearDownloadState(context);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, APK_MIME);
            install.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
            context.startActivity(install);
            NotificationHelper.show(
                context,
                "Phone Runner",
                "更新已下載，請按一次確認安裝"
            );
        } catch (Exception e) {
            manager.remove(id);
            fail(context, "更新檔案無效");
        }
    }

    private static boolean successful(DownloadManager manager, long id) {
        try (Cursor cursor = manager.query(
            new DownloadManager.Query().setFilterById(id)
        )) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            int status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            );
            return status == DownloadManager.STATUS_SUCCESSFUL;
        } catch (Exception e) {
            return false;
        }
    }

    private static void fail(Context context, String message) {
        UpdateChecker.clearDownloadState(context);
        NotificationHelper.show(context, "Phone Runner 更新失敗", message);
    }
}
