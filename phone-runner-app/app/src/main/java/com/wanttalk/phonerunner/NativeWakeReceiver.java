package com.wanttalk.phonerunner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class NativeWakeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            NativeWakeScheduler.ensureScheduled(context);
            return;
        }
        if (!NativeWakeScheduler.ACTION_NATIVE_TICK.equals(action)) return;

        NativeWakeScheduler.ensureScheduled(context);
        String requestId = "native-alarm-" + System.currentTimeMillis();
        RunnerState.recordReceived(context, requestId, "NATIVE_ALARM");
        DeviceRegistration.reportPhase(context, "received", requestId, "NATIVE_ALARM");
        NotificationHelper.show(context, "Phone Runner", "原生排程喚醒，正在交給 Termux");

        try {
            TermuxCommandClient.dispatchRunner(context, requestId);
            RunnerState.recordDispatch(context, requestId, "NATIVE_ALARM_DISPATCHED");
            DeviceRegistration.reportPhase(context, "dispatched", requestId, "NATIVE_ALARM");
        } catch (Exception e) {
            RunnerState.recordDispatch(
                context,
                requestId,
                "FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage()
            );
            DeviceRegistration.reportPhase(context, "dispatch_failed", requestId, "NATIVE_ALARM");
            NotificationHelper.show(
                context,
                "Phone Runner 無法執行",
                e.getClass().getSimpleName() + ": " + e.getMessage()
            );
        }
    }
}
