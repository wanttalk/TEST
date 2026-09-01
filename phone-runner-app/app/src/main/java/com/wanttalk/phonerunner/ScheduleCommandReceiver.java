package com.wanttalk.phonerunner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ScheduleCommandReceiver extends BroadcastReceiver {
    static final String ACTION_SET_SCHEDULE =
        "com.wanttalk.phonerunner.SET_SCHEDULE";
    private static final String EXTRA_INTERVAL_MINUTES = "interval_minutes";
    private static final String EXTRA_REQUEST_ID = "request_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SET_SCHEDULE.equals(intent.getAction())) {
            return;
        }

        long minutes = intent.getLongExtra(EXTRA_INTERVAL_MINUTES, -1L);
        String requestId = intent.getStringExtra(EXTRA_REQUEST_ID);
        if (requestId == null || requestId.trim().isEmpty()) {
            requestId = "schedule-" + System.currentTimeMillis();
        }

        boolean scheduled = NativeWakeScheduler.scheduleMinutes(context, minutes);
        String result = scheduled
            ? "SCHEDULED interval=" + minutes + "m"
            : "FAILED invalid interval=" + minutes + "m";
        RunnerState.recordDispatch(context, requestId, result);
        DeviceRegistration.reportPhase(
            context,
            scheduled ? "dispatched" : "dispatch_failed",
            requestId,
            "NORMAL"
        );
        NotificationHelper.show(
            context,
            "Phone Runner",
            scheduled
                ? "已套用對話排程：每 " + minutes + " 分鐘"
                : "對話排程無效，請檢查分鐘數"
        );
        setResultCode(scheduled ? 0 : 1);
    }
}
