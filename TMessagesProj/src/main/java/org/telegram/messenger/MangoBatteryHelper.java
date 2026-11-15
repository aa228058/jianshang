package org.telegram.messenger;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 保活第 2 层：让系统别把进程掐了。
 *
 * - 标准做法：申请电池优化白名单（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）
 * - 国产 ROM：还要单独跳「自启动 / 后台运行」页面，标准 API 管不到
 */
public final class MangoBatteryHelper {

    private MangoBatteryHelper() {
    }

    /** 已经进了电池优化白名单？ */
    public static boolean isIgnoringBatteryOptimizations() {
        try {
            if (Build.VERSION.SDK_INT < 23) {
                return true;
            }
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return true;
            }
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable t) {
            return true;
        }
    }

    /** 系统弹窗：允许后台运行。 */
    public static Intent batteryRequestIntent() {
        try {
            if (Build.VERSION.SDK_INT < 23) {
                return null;
            }
            Context context = ApplicationLoader.applicationContext;
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 跳国产 ROM 的自启动/后台管理页。逐个厂商试，都不中就退到应用详情页。
     */
    public static Intent autoStartIntent() {
        Context context = ApplicationLoader.applicationContext;
        String pkg = context.getPackageName();
        String[][] candidates = new String[][]{
                // 小米
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                // 华为
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                // OPPO
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                // vivo
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                // 三星
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
        };
        for (String[] c : candidates) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(c[0], c[1]));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (context.getPackageManager().resolveActivity(intent, 0) != null) {
                    return intent;
                }
            } catch (Throwable ignore) {
            }
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Throwable t) {
            return null;
        }
    }
}
