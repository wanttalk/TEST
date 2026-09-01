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
        schedule(context, PERIOD_MS);
    }

    static void scheduleTest(Context context) {
        schedule(context, 60L * 1000L);
    }

    private static void schedule(Context context, long delayMs) {
        Context appContext = context.getApplicationContext();
        AlarmManager alarmManager =
            (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        PendingIntent operation = pendingIntent(appContext);
        long triggerAt = SystemClock.elapsedRealtime() + delayMs;
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

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, NativeWakeReceiver.class)
            .setAction(ACTION_NATIVE_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
