package com.wanttalk.phonerunner;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

final class NativeWakeScheduler {
    static final String ACTION_NATIVE_TICK =
        "com.wanttalk.phonerunner.NATIVE_TICK";
    private static final int REQUEST_CODE = 3702;
    private static final long PERIOD_MS = 60L * 60L * 1000L;

    private NativeWakeScheduler() {}

    static void ensureScheduled(Context context) {
        Context appContext = context.getApplicationContext();
        AlarmManager alarmManager =
            (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        PendingIntent operation = pendingIntent(appContext, false);
        long triggerAt = SystemClock.elapsedRealtime() + PERIOD_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                operation
            );
        } else {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                operation
            );
        }
        RunnerState.recordNativeAlarmScheduled(appContext);
    }

    private static PendingIntent pendingIntent(Context context, boolean noCreate) {
        Intent intent = new Intent(context, NativeWakeReceiver.class)
            .setAction(ACTION_NATIVE_TICK);
        int flags = noCreate ? PendingIntent.FLAG_NO_CREATE : PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
