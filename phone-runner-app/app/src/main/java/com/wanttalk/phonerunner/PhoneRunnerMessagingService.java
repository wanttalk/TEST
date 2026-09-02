package com.wanttalk.phonerunner;

import android.os.Build;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public final class PhoneRunnerMessagingService extends FirebaseMessagingService {
    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        NativeWakeScheduler.ensureScheduled(this);
        RunnerState.recordToken(this, token);
        DeviceRegistration.register(this, token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);

        String action = message.getData().getOrDefault("action", "");
        if ("schedule_set".equals(action)) {
            handleScheduleSet(message);
            return;
        }
        if (!"wake".equals(action)) {
            return;
        }

        NativeWakeScheduler.ensureScheduled(this);

        String requestId = message.getData().getOrDefault(
            "request_id",
            "fcm-" + System.currentTimeMillis()
        );

        if (RunnerState.isDuplicate(this, requestId)) {
            return;
        }

        boolean highPriority = message.getPriority() == RemoteMessage.PRIORITY_HIGH;
        String priority = highPriority ? "HIGH" : "NORMAL";
        RunnerState.recordReceived(this, requestId, priority);
        DeviceRegistration.reportPhase(this, "received", requestId, priority);

        NotificationHelper.show(
            this,
            "Phone Runner",
            highPriority
                ? "收到遠端喚醒，正在交給 Termux"
                : "收到遠端喚醒（FCM 已降級），仍嘗試交給 Termux"
        );

        int termuxTargetSdk = TermuxCommandClient.termuxTargetSdk(this);
        if (!highPriority
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && termuxTargetSdk >= Build.VERSION_CODES.S) {
            RunnerState.recordDispatch(
                this,
                requestId,
                "BLOCKED_LOW_PRIORITY_TERMUX_TARGET_" + termuxTargetSdk
            );
            DeviceRegistration.reportPhase(this, "dispatch_blocked", requestId, priority);
            NotificationHelper.show(
                this,
                "Phone Runner 未執行",
                "FCM 被降級且 Termux targetSdk=" + termuxTargetSdk
            );
            return;
        }

        try {
            TermuxCommandClient.dispatchRunner(this, requestId);
            RunnerState.recordDispatch(
                this,
                requestId,
                "DISPATCHED priority=" + priority + " termuxTarget=" + termuxTargetSdk
            );
            DeviceRegistration.reportPhase(this, "dispatched", requestId, priority);
        } catch (Exception e) {
            RunnerState.recordDispatch(
                this,
                requestId,
                "FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage()
            );
            DeviceRegistration.reportPhase(this, "dispatch_failed", requestId, priority);
            NotificationHelper.show(
                this,
                "Phone Runner 無法執行",
                e.getClass().getSimpleName() + ": " + e.getMessage()
            );
        }
    }

    private void handleScheduleSet(RemoteMessage message) {
        NativeWakeScheduler.ensureScheduled(this);

        String requestId = message.getData().getOrDefault(
            "request_id",
            "schedule-" + System.currentTimeMillis()
        );
        if (RunnerState.isDuplicate(this, requestId)) {
            return;
        }

        String priority = message.getPriority() == RemoteMessage.PRIORITY_HIGH
            ? "HIGH"
            : "NORMAL";
        RunnerState.recordReceived(this, requestId, priority);
        DeviceRegistration.reportPhase(this, "received", requestId, priority);

        long minutes;
        try {
            minutes = Long.parseLong(
                message.getData().getOrDefault("interval_minutes", "")
            );
        } catch (NumberFormatException e) {
            recordScheduleFailure(requestId, priority, "invalid interval");
            return;
        }

        if (!NativeWakeScheduler.scheduleMinutes(this, minutes)) {
            recordScheduleFailure(requestId, priority, "interval must be 1-10080 minutes");
            return;
        }

        RunnerState.recordDispatch(
            this,
            requestId,
            "SCHEDULE_SET interval=" + minutes + "m"
        );
        DeviceRegistration.reportPhase(this, "dispatched", requestId, priority);
        NotificationHelper.show(
            this,
            "Phone Runner",
            "已套用對話排程：每 " + minutes + " 分鐘"
        );

        try {
            TermuxCommandClient.dispatchAppliedSchedule(this, requestId);
        } catch (Exception e) {
            RunnerState.recordDispatch(
                this,
                requestId,
                "SCHEDULE_SET interval=" + minutes + "m ACK_FAILED"
            );
            NotificationHelper.show(
                this,
                "Phone Runner",
                "排程已設定，但測試回報未送到 Termux"
            );
        }
    }

    private void recordScheduleFailure(String requestId, String priority, String reason) {
        RunnerState.recordDispatch(this, requestId, "SCHEDULE_FAILED: " + reason);
        DeviceRegistration.reportPhase(this, "dispatch_failed", requestId, priority);
        NotificationHelper.show(this, "Phone Runner", "對話排程無效：" + reason);
    }
}
