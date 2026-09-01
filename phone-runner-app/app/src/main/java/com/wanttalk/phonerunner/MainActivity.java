package com.wanttalk.phonerunner;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 3701;
    private TextView statusView;
    private EditText workerUrlInput;
    private EditText registrationTokenInput;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DeviceRegistration.ensureBuiltInConfiguration(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Android Phone Runner");
        title.setTextSize(24);
        content.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.setMargins(0, dp(18), 0, dp(18));
        content.addView(statusView, statusParams);

        TextView pairingTitle = new TextView(this);
        pairingTitle.setText("一次性配對");
        pairingTitle.setTextSize(18);
        content.addView(pairingTitle);

        workerUrlInput = new EditText(this);
        workerUrlInput.setHint("Worker URL，例如 https://phone-runner.example.workers.dev");
        workerUrlInput.setSingleLine(true);
        workerUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        workerUrlInput.setText(DeviceRegistration.getWorkerUrl(this));
        content.addView(workerUrlInput);

        registrationTokenInput = new EditText(this);
        registrationTokenInput.setHint("一次性配對碼");
        registrationTokenInput.setSingleLine(true);
        registrationTokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        registrationTokenInput.setText(DeviceRegistration.getPairingToken(this));
        content.addView(registrationTokenInput);

        Button savePairing = button("儲存配對並登記這支手機");
        savePairing.setOnClickListener(v -> savePairing());
        content.addView(savePairing);

        if (BuildConfig.PHONE_RUNNER_BOOTSTRAP_TOKEN != null
            && BuildConfig.PHONE_RUNNER_BOOTSTRAP_TOKEN.trim().length() >= 20) {
            pairingTitle.setVisibility(View.GONE);
            workerUrlInput.setVisibility(View.GONE);
            registrationTokenInput.setVisibility(View.GONE);
            savePairing.setVisibility(View.GONE);
        }

        Button permission = button("授權通知與 Termux");
        permission.setOnClickListener(v -> requestNeededPermissions());
        content.addView(permission);

        Button battery = button("允許 Phone Runner 背景執行");
        battery.setOnClickListener(v -> PowerPolicy.requestBatteryOptimizationExemption(this));
        content.addView(battery);

        Button vendorBackground = button(PowerPolicy.isXiaomiFamily() ? "開啟小米自啟動／背景設定" : "開啟廠牌背景設定");
        vendorBackground.setOnClickListener(v -> {
            if (!PowerPolicy.openVendorBackgroundSettings(this)) {
                Toast.makeText(this, "無法直接開啟背景設定", Toast.LENGTH_LONG).show();
            }
        });
        content.addView(vendorBackground);

        Button termuxSettings = button("開啟 Termux 系統設定");
        termuxSettings.setOnClickListener(v -> PowerPolicy.openTermuxAppSettings(this));
        content.addView(termuxSettings);

        Button test = button("測試 Termux 橋接");
        test.setOnClickListener(v -> runBridgeTest());
        content.addView(test);

        Button runNow = button("立即執行 Runner");
        runNow.setOnClickListener(v -> runRunnerNow());
        content.addView(runNow);

        Button copyToken = button("複製 FCM Token（除錯用）");
        copyToken.setOnClickListener(v -> copyToken());
        content.addView(copyToken);

        Button refresh = button("重新整理");
        refresh.setOnClickListener(v -> refreshStatus());
        content.addView(refresh);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);

        handlePairingIntent(getIntent());
        requestNeededPermissions();
        refreshFirebaseToken();
        refreshStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePairingIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        return button;
    }

    private void handlePairingIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        if (!"phonerunner".equalsIgnoreCase(data.getScheme())) return;
        if (!"pair".equalsIgnoreCase(data.getHost())) return;

        String workerUrl = data.getQueryParameter("url");
        String pairingCode = data.getQueryParameter("code");
        if (workerUrl == null || pairingCode == null) return;

        try {
            DeviceRegistration.saveConfiguration(this, workerUrl, pairingCode);
            workerUrlInput.setText(DeviceRegistration.getWorkerUrl(this));
            registrationTokenInput.setText(DeviceRegistration.getPairingToken(this));
            DeviceRegistration.registerCurrentToken(this);
            Toast.makeText(this, "配對資料已匯入，正在登記這支手機", Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
        refreshStatus();
    }

    private void requestNeededPermissions() {
        java.util.ArrayList<String> wanted = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(TermuxCommandClient.TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(TermuxCommandClient.TERMUX_PERMISSION);
        }
        if (!wanted.isEmpty()) requestPermissions(wanted.toArray(new String[0]), PERMISSION_REQUEST);
    }

    private void savePairing() {
        try {
            DeviceRegistration.saveConfiguration(this, workerUrlInput.getText().toString(), registrationTokenInput.getText().toString());
            DeviceRegistration.registerCurrentToken(this);
            Toast.makeText(this, "配對資料已儲存，正在登記 FCM Token", Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
        refreshStatus();
    }

    private void runBridgeTest() {
        String requestId = "health-" + System.currentTimeMillis();
        try {
            NotificationHelper.show(this, "Phone Runner", "正在測試 Termux 橋接");
            TermuxCommandClient.dispatchHealthCheck(this, requestId);
            RunnerState.recordDispatch(this, requestId, "HEALTH_DISPATCHED");
            Toast.makeText(this, "橋接測試已送出", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { recordManualFailure(requestId, e); }
        refreshStatus();
    }

    private void runRunnerNow() {
        String requestId = "manual-runner-" + System.currentTimeMillis();
        try {
            NotificationHelper.show(this, "Phone Runner", "正在執行 phone_tick.sh");
            TermuxCommandClient.dispatchRunner(this, requestId);
            RunnerState.recordDispatch(this, requestId, "RUNNER_DISPATCHED");
            Toast.makeText(this, "Runner 已送到 Termux", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { recordManualFailure(requestId, e); }
        refreshStatus();
    }

    private void recordManualFailure(String requestId, Exception e) {
        RunnerState.recordDispatch(this, requestId, "FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
    }

    private void refreshFirebaseToken() {
        if (FirebaseApp.getApps(this).isEmpty()) return;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            if (token != null && !token.trim().isEmpty()) {
                RunnerState.recordToken(this, token);
                DeviceRegistration.register(this, token);
                refreshStatus();
            }
        });
    }

    private void copyToken() {
        String token = RunnerState.getToken(this);
        if (token.trim().isEmpty()) {
            Toast.makeText(this, "尚未取得 FCM Token", Toast.LENGTH_LONG).show();
            refreshFirebaseToken();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("FCM token", token));
            Toast.makeText(this, "FCM Token 已複製", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshStatus() {
        boolean firebase = !FirebaseApp.getApps(this).isEmpty();
        boolean termux = TermuxCommandClient.isTermuxInstalled(this);
        boolean termuxPermission = TermuxCommandClient.hasRunCommandPermission(this);
        boolean notificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean batteryExempt = PowerPolicy.isBatteryOptimizationIgnored(this);
        boolean hasToken = !RunnerState.getToken(this).trim().isEmpty();
        boolean pairingConfigured = DeviceRegistration.isConfigured(this);
        boolean paired = DeviceRegistration.isPaired(this);
        boolean registered = "REGISTERED".equals(RunnerState.registrationStatus(this));

        statusView.setText(
            "Firebase: " + mark(firebase)
                + "\nFCM Token: " + mark(hasToken)
                + "\nWorker 配對資料: " + mark(pairingConfigured)
                + "\n裝置專用密鑰: " + mark(paired)
                + "\n裝置已登記: " + mark(registered)
                + "\nTermux: " + mark(termux)
                + "\nTermux RUN_COMMAND 權限: " + mark(termuxPermission)
                + "\n通知權限: " + mark(notificationPermission)
                + "\nPhone Runner 電池不受限: " + mark(batteryExempt)
                + "\n裝置廠牌: " + (PowerPolicy.isXiaomiFamily() ? "Xiaomi/Redmi/POCO" : Build.MANUFACTURER)
                + "\n\n" + RunnerState.summary(this)
                + "\n\nTermux 另需設定 allow-external-apps=true，並建議將 Termux 電池使用設為不限制。"
        );
    }

    private String mark(boolean ok) { return ok ? "🟢" : "🔴"; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
