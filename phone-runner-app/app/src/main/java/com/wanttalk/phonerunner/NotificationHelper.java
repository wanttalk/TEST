package com.wanttalk.phonerunner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

final class NotificationHelper {
    private static final String CHANNEL_ID = "phone_runner_wake";
    private static final int NOTIFICATION_ID = 3701;

    private NotificationHelper() {}

    static void show(Context context, String title, String text) {
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Phone Runner",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Remote maintenance wake and Termux execution status");
            manager.createNotificationChannel(channel);
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(context, CHANNEL_ID)
            : new Notification.Builder(context);

        Notification notification = builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setShowWhen(true)
            .build();

        manager.notify(NOTIFICATION_ID, notification);
    }
}
