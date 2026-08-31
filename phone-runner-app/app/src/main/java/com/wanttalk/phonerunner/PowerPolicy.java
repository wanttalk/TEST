package com.wanttalk.phonerunner;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Locale;

final class PowerPolicy {
    private PowerPolicy() {}

    static boolean isBatteryOptimizationIgnored(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager manager =
            (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return manager != null
            && manager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    static boolean isXiaomiFamily() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String brand = Build.BRAND == null ? "" : Build.BRAND;
        String combined = (manufacturer + " " + brand).toLowerCase(Locale.ROOT);
        return combined.contains("xiaomi")
            || combined.contains("redmi")
            || combined.contains("poco");
    }

    static void requestBatteryOptimizationExemption(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        Intent intent = new Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + context.getPackageName())
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    static boolean openVendorBackgroundSettings(Context context) {
        if (isXiaomiFamily()) {
            Intent[] candidates = new Intent[]{
                componentIntent(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                ),
                new Intent("miui.intent.action.OP_AUTO_START"),
                componentIntent(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                ).putExtra("package_name", context.getPackageName())
                 .putExtra(
                     "package_label",
                     context.getApplicationInfo().loadLabel(context.getPackageManager())
                 )
            };
            for (Intent candidate : candidates) {
                if (tryStart(context, candidate)) return true;
            }
        }

        return openOwnAppSettings(context);
    }

    static void openTermuxAppSettings(Context context) {
        Intent intent = new Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + TermuxCommandClient.TERMUX_PACKAGE)
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    static boolean openOwnAppSettings(Context context) {
        return tryStart(
            context,
            new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName())
            )
        );
    }

    private static Intent componentIntent(String packageName, String className) {
        return new Intent().setComponent(new ComponentName(packageName, className));
    }

    private static boolean tryStart(Context context, Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
