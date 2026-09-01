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
    private static final long DEFAULT_INTERVAL_MINUTES = 60L;
    private static final long MIN_INTERVAL_MINUTES = 1L;
    private static final long MAX_INTERVAL_MINUTES = 7L * 24L * 60L;
    private static final String PREFS = "native_schedule";
    private static final String KEY_INTERVAL_MINUTES = "interval_minutes";

    private NativeWakeScheduler() {}

    static void ensureScheduled(Context context) {
        schedule(context, getIntervalMinutes(context) * 60L * 1000L);
    }

    static long getIntervalMinutes(Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES);
    }

    static boolean scheduleMinutes(Context context, long minutes) {
        if (minutes < MIN_INTERVAL_MINUTES || minutes > MAX_INTERVAL_MINUTES) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_INTERVAL_MINUTES, minutes)
            .apply();
        schedule(appContext, minutes * 60L * 1000L);
        return true;
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
