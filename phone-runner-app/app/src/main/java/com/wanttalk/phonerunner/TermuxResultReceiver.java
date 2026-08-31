package com.wanttalk.phonerunner;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class TermuxResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String requestId = intent.getStringExtra("request_id");
        if (requestId == null || requestId.trim().isEmpty()) requestId = "unknown";

        Bundle result = intent.getBundleExtra("result");
        if (result == null) {
            RunnerState.recordResult(
                context, requestId, -1, -1, "", "",
                "Termux returned no result bundle"
            );
            DeviceRegistration.reportResult(context, requestId, -1, -1);
            NotificationHelper.show(context, "Phone Runner 失敗", "Termux 沒有回傳結果");
            return;
        }

        int err = result.getInt("err", Integer.MIN_VALUE);
        int exitCode = result.getInt("exitCode", -1);
        String stdout = result.getString("stdout", "");
        String stderr = result.getString("stderr", "");
        String errmsg = result.getString("errmsg", "");

        RunnerState.recordResult(
            context, requestId, err, exitCode, stdout, stderr, errmsg
        );

        boolean ok = err == Activity.RESULT_OK && exitCode == 0;
        int reportErr = ok ? 0 : err;
        DeviceRegistration.reportResult(context, requestId, reportErr, exitCode);

        NotificationHelper.show(
            context,
            ok ? "Phone Runner 完成" : "Phone Runner 失敗",
            ok ? "Termux 任務 PASS" : "err=" + err + " exit=" + exitCode
        );
    }
}
