package com.wanttalk.phonerunner;

import android.content.Context;
import android.content.SharedPreferences;

final class RunnerState {
    private static final String PREFS = "phone_runner_state";

    private RunnerState() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void recordToken(Context context, String token) {
        prefs(context).edit().putString("fcm_token", token).apply();
    }

    static String getToken(Context context) {
        return prefs(context).getString("fcm_token", "");
    }

    static void recordRegistration(Context context, String status) {
        prefs(context).edit()
            .putString("registration_status", tail(status, 600))
            .putString("registration_at", Long.toString(System.currentTimeMillis()))
            .apply();
    }

    static String registrationStatus(Context context) {
        return prefs(context).getString("registration_status", "-");
    }

    static boolean isDuplicate(Context context, String requestId) {
        return !requestId.isEmpty()
            && requestId.equals(prefs(context).getString("last_request_id", ""));
    }

    static void recordReceived(Context context, String requestId, String priority) {
        prefs(context).edit()
            .putString("last_request_id", requestId)
            .putString("last_received_at", Long.toString(System.currentTimeMillis()))
            .putString("last_priority", priority)
            .apply();
    }

    static void recordDispatch(Context context, String requestId, String status) {
        prefs(context).edit()
            .putString("last_dispatch_request_id", requestId)
            .putString("last_dispatch_at", Long.toString(System.currentTimeMillis()))
            .putString("last_dispatch_status", status)
            .apply();
    }

    static void recordResult(
        Context context,
        String requestId,
        int err,
        int exitCode,
        String stdout,
        String stderr,
        String errmsg
    ) {
        prefs(context).edit()
            .putString("last_result_request_id", requestId)
            .putString("last_result_at", Long.toString(System.currentTimeMillis()))
            .putInt("last_err", err)
            .putInt("last_exit_code", exitCode)
            .putString("last_stdout", tail(stdout, 4000))
            .putString("last_stderr", tail(stderr, 4000))
            .putString("last_errmsg", tail(errmsg, 1000))
            .apply();
    }

    static String summary(Context context) {
        SharedPreferences p = prefs(context);
        String requestId = p.getString("last_result_request_id", "-");
        int err = p.getInt("last_err", Integer.MIN_VALUE);
        int exitCode = p.getInt("last_exit_code", Integer.MIN_VALUE);
        String dispatch = p.getString("last_dispatch_status", "-");
        String received = p.getString("last_received_at", "-");
        String result = p.getString("last_result_at", "-");
        String registration = p.getString("registration_status", "-");
        String registrationAt = p.getString("registration_at", "-");
        return "裝置登記: " + registration
            + "\n登記時間: " + registrationAt
            + "\n最後收到: " + received
            + "\n最後派送: " + dispatch
            + "\n最後結果 request: " + requestId
            + "\nTermux err: " + (err == Integer.MIN_VALUE ? "-" : err)
            + " / exit: " + (exitCode == Integer.MIN_VALUE ? "-" : exitCode)
            + "\n結果時間: " + result;
    }

    private static String tail(String value, int limit) {
        if (value == null) return "";
        if (value.length() <= limit) return value;
        return value.substring(value.length() - limit);
    }
}
