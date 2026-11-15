package org.telegram.messenger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;

/**
 * Mango 常驻服（保活）。
 *
 * 为什么需要它：Telegram 的 MTProto 长连接在 app 进后台时会被 pause，
 * 于是「新消息 → 自动已读 / 自动回复 / 关键词提醒」这条链就断在源头。
 * 光持 CPU 唤醒锁没用，必须周期性调 ConnectionsManager.resumeNetworkMaybe()。
 *
 * 前台服务类型用 specialUse 而不是 dataSync：
 *   Android 15 给 dataSync 每天 6 小时预算，到点系统会回调 onTimeout 掐掉；
 *   specialUse 没有这个时间预算。
 */
public final class MangoResidentService extends Service {

    private static final int NOTIFICATION_ID = 0x4d4753; // "MGS"
    /** 电池优化引导用的独立通知 id。 */
    private static final int BATTERY_NOTIFICATION_ID = 0x4d4754;
    private static final String BATTERY_CHANNEL = "mango_battery";
    private static final String CHANNEL = "mango_resident";
    private static final String ACTION_STOP = "mango.resident.stop";

    /** 屏幕亮着时快一点，熄屏后慢一点，省电。 */
    private static final long TICK_SCREEN_ON = 1000L;
    private static final long TICK_SCREEN_OFF = 3000L;
    /** 网络唤醒间隔，和 MangoUserBroadcastManager.INTERVAL_MS 对齐。 */
    private static final long NET_WAKE_GAP = MangoUserBroadcastManager.INTERVAL_MS;

    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private final long[] nextNetWake = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private volatile boolean killed;
    /** 服务是不是已经在跑，避免每 5 秒重复 startForegroundService 被系统拦。 */
    private static volatile boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (killed) {
                return;
            }
            if (!isNeeded()) {
                stopSelf();
                return;
            }
            final long now = SystemClock.elapsedRealtime();
            for (int account : UserConfig.getActivatedAccounts()) {
                if (now >= nextNetWake[account]) {
                    // 只"温和恢复"，不把账号标成在线。
                    ConnectionsManager.getInstance(account).resumeNetworkMaybe();
                    nextNetWake[account] = now + NET_WAKE_GAP;
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(10 * 60 * 1000L);
            }
            showNotification();
            handler.postDelayed(this, isScreenOn() ? TICK_SCREEN_ON : TICK_SCREEN_OFF);
        }
    };

    // ------------------------------------------------------------ lifecycle

    /** 有任一账号开着需要后台的功能时才常驻。 */
    public static boolean isNeeded() {
        for (int account : UserConfig.getActivatedAccounts()) {
            if (MangoSettings.getBoolean(account, "auto_read")
                    || MangoSettings.getBoolean(account, "auto_read_random_reply")
                    || MangoSettings.getBoolean(account, "resident_keep_alive")) {
                return true;
            }
        }
        return false;
    }

    public static void startIfNeeded() {
        if (running) {
            return;
        }
        if (isNeeded()) {
            start();
        }
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean start() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return false;
            }
            Intent intent = new Intent(context, MangoResidentService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    public static void stop() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                context.stopService(new Intent(context, MangoResidentService.class));
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        ApplicationLoader.postInitApplication();
        handler = new Handler(getMainLooper());
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mango:resident");
            wakeLock.setReferenceCounted(false);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "活动",
                    NotificationManager.IMPORTANCE_MIN);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须在 5 秒内 startForeground，先兜一发再判断。
        showNotification();
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            killed = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!isNeeded()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        killed = false;
        maybePromptBattery();
        handler.removeCallbacks(tick);
        handler.post(tick);
        // 被系统杀进程后由系统重新拉起。
        return START_STICKY;
    }

    /** Android 15 的 dataSync 预算回调；specialUse 类型下一般不会走到。 */
    public void onTimeout(int startId, int fgsType) {
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        killed = true;
        if (handler != null) {
            handler.removeCallbacks(tick);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // -------------------------------------------------------------- helpers

    private static boolean isScreenOn() {
        PowerManager power = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isInteractive();
    }

    /**
     * 第 2 层保活：没进电池优化白名单就弹一条引导通知。
     * 点「允许后台」直接唤起系统授权弹窗；点「自启动设置」跳国产 ROM 的自启动页。
     */
    private void maybePromptBattery() {
        try {
            if (MangoBatteryHelper.isIgnoringBatteryOptimizations()) {
                return;
            }
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(BATTERY_CHANNEL,
                        "后台运行", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setSound(null, null);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            Intent batteryIntent = MangoBatteryHelper.batteryRequestIntent();
            if (batteryIntent == null) {
                return;
            }
            PendingIntent allow = PendingIntent.getActivity(this, BATTERY_NOTIFICATION_ID, batteryIntent, flags);
            Intent autoStart = MangoBatteryHelper.autoStartIntent();
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, BATTERY_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("让见山客户端在后台活下来")
                    .setContentText("点「允许后台」关闭电池优化，否则自动化会被系统掐断")
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(
                            "点「允许后台」关闭电池优化，否则自动化会被系统掐断；"
                                    + "国产 ROM 还要在「自启动设置」里手动放行。"))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(allow)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .addAction(0, "允许后台", allow);
            if (autoStart != null) {
                builder.addAction(0, "自启动设置",
                        PendingIntent.getActivity(this, BATTERY_NOTIFICATION_ID + 1, autoStart, flags));
            }
            manager.notify(BATTERY_NOTIFICATION_ID, builder.build());
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private void showNotification() {
        Context context = this;
        Intent open = new Intent(context, LaunchActivity.class).setAction("mango.resident");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(context, NOTIFICATION_ID, open, flags);
        PendingIntent cancel = PendingIntent.getService(context, NOTIFICATION_ID,
                new Intent(context, MangoResidentService.class).setAction(ACTION_STOP), flags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("活动")
                .setContentText("后台自动化已开启")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(content)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .addAction(0, "取消", cancel);
        startForeground(NOTIFICATION_ID, builder.build());
    }
}
