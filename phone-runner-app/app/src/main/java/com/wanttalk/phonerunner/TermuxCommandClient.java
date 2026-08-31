package com.wanttalk.phonerunner;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

final class TermuxCommandClient {
    static final String TERMUX_PACKAGE = "com.termux";
    static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";

    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";

    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String RUNNER_DIR =
        "/data/data/com.termux/files/home/projects/android-phone-runner";
    private static final String RUNNER_SCRIPT = RUNNER_DIR + "/phone_tick.sh";

    private TermuxCommandClient() {}

    static boolean isTermuxInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    static int termuxTargetSdk(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(TERMUX_PACKAGE, 0);
            return info.targetSdkVersion;
        } catch (PackageManager.NameNotFoundException ignored) {
            return -1;
        }
    }

    static boolean hasRunCommandPermission(Context context) {
        return context.checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static void dispatchRunner(Context context, String requestId) {
        dispatch(context, requestId, new String[]{RUNNER_SCRIPT});
    }

    static void dispatchHealthCheck(Context context, String requestId) {
        dispatch(context, requestId, new String[]{"-lc", "printf 'PHONE_RUNNER_BRIDGE_OK\\n'"});
    }

    private static void dispatch(Context context, String requestId, String[] arguments) {
        if (!isTermuxInstalled(context)) {
            throw new IllegalStateException("Termux is not installed");
        }
        if (!hasRunCommandPermission(context)) {
            throw new SecurityException("RUN_COMMAND permission is not granted");
        }

        Intent callback = new Intent(context, TermuxResultReceiver.class)
            .setAction("com.wanttalk.phonerunner.TERMUX_RESULT")
            .putExtra("request_id", requestId);

        int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }

        PendingIntent resultIntent = PendingIntent.getBroadcast(
            context,
            requestId.hashCode(),
            callback,
            flags
        );

        Intent command = new Intent(ACTION_RUN_COMMAND);
        command.setComponent(new ComponentName(
            TERMUX_PACKAGE,
            "com.termux.app.RunCommandService"
        ));
        command.putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH);
        command.putExtra(EXTRA_ARGUMENTS, arguments);
        command.putExtra(EXTRA_WORKDIR, RUNNER_DIR);
        command.putExtra(EXTRA_BACKGROUND, true);
        command.putExtra(EXTRA_PENDING_INTENT, resultIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(command);
        } else {
            context.startService(command);
        }
    }
}
