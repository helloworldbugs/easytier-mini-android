package com.kkrainbow.easytier.mini;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import java.io.File;

/**
 * Keeps easytier-core alive while the UI is closed and auto-runs the last config.
 *
 * <p>Restart semantics: START_STICKY. If Android restarts the service after a
 * kill, it comes back with a null Intent and the config is re-run -- but only when
 * the user's auto-run switch is on, so an explicit "stop" stays stopped.
 */
public class CoreService extends Service {

    public static final String ACTION_START = "com.kkrainbow.easytier.mini.action.START";
    public static final String ACTION_STOP = "com.kkrainbow.easytier.mini.action.STOP";
    public static final String ACTION_SHUTDOWN = "com.kkrainbow.easytier.mini.action.SHUTDOWN";
    public static final String EXTRA_CONFIG = "config";

    private static final String CHANNEL_ID = "core";
    private static final int NOTIF_ID = 1;

    private final Core.Listener coreListener = new Core.Listener() {
        @Override
        public void onCoreChanged() {
            onCoreStateChanged();
        }
    };

    private volatile boolean coreStarted;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "EasyTier Mini", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Core.get().addListener(coreListener);
    }

    @Override
    public void onDestroy() {
        Core.get().removeListener(coreListener);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_SHUTDOWN.equals(action)) {
            startForeground(NOTIF_ID, notification("正在结束 EasyTier Mini…"));
            shutdownAsync();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            stopAsync();
            return START_NOT_STICKY;
        }

        Prefs prefs = new Prefs(this);
        boolean stickyRestart = intent == null;
        if (!stickyRestart) {
            // An explicit start always clears a prior shutdown request.
            prefs.setShutdownRequested(false);
        }
        String path = intent != null ? intent.getStringExtra(EXTRA_CONFIG) : null;
        if (path == null) {
            path = prefs.lastConfig();
        }

        startForeground(NOTIF_ID, notification("正在启动…"));

        if (path == null) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (stickyRestart && (!prefs.autoRun() || prefs.shutdownRequested())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        File cfg = new File(path);
        if (!cfg.isFile()) {
            Core.get().logs().add("!! 配置文件不存在: " + path);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        startCoreAsync(cfg);
        return START_STICKY;
    }

    /**
     * Stops the core and then takes the notification down. Off the main thread on
     * purpose: {@code Core.stop()} waits up to 8s for the child to die, and this path
     * runs from {@code onStartCommand} -- i.e. already on the main thread.
     */
    private void stopAsync() {
        new Thread(() -> {
            Core.get().stop();
            new Handler(Looper.getMainLooper()).post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        }, "ctl-core-stop").start();
    }

    /**
     * Graceful full teardown for the headless control path: stop the core, take the
     * notification down, then kill our own process.
     *
     * <p>The 1.5s delay before killing is deliberate: SIGKILLing the process while the
     * platform still considers this service "start requested" makes it resurrect the
     * START_STICKY service -- and a sticky restart would auto-run the config again,
     * which is exactly what "force stop" must not do. Waiting lets stopSelf() land first.
     */
    private void shutdownAsync() {
        new Thread(() -> {
            Core.get().stop();
            new Prefs(CoreService.this).setShutdownRequested(true);
            new Handler(Looper.getMainLooper()).post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> Process.killProcess(Process.myPid()), 1500L);
            });
        }, "ctl-shutdown").start();
    }

    private void startCoreAsync(final File cfg) {
        new Thread(() -> {
            boolean ok = Core.get().start(getApplicationContext(), cfg);
            coreStarted = ok;
            notify(ok ? "运行中 · " + cfg.getName()
                    : "启动失败 · " + Core.get().lastError());
            if (!ok) {
                sleep(2500);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }, "core-start").start();
    }

    private void onCoreStateChanged() {
        if (Core.get().isRunning()) {
            coreStarted = true;
            return;
        }
        if (!coreStarted) {
            return;
        }
        coreStarted = false;
        notify("已退出" + (Core.get().lastError() == null ? "" : " · " + Core.get().lastError()));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }, 1500);
    }

    private void notify(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, notification(text));
        }
    }

    private Notification notification(String text) {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, CoreService.class).setAction(ACTION_STOP), piFlags);
        PendingIntent openPi = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("EasyTier Mini")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(0, "停止", stopPi)
                .build();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
