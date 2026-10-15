package com.kkrainbow.easytier.mini;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;

/**
 * Headless control surface for on-device automation (adb shell / scripts).
 *
 * <p>Deliberately exported with no permission: adb shell (uid 2000) does not hold
 * custom permissions, so a permission here would make shell automation impossible.
 * A config path is validated against ConfigStore's own list -- never an arbitrary file.
 */
public class ControlReceiver extends BroadcastReceiver {

    public static final String ACTION_STATUS = "com.kkrainbow.easytier.mini.action.STATUS";
    public static final String ACTION_LIST = "com.kkrainbow.easytier.mini.action.LIST";
    public static final String ACTION_SHUTDOWN = "com.kkrainbow.easytier.mini.action.SHUTDOWN";

    @Override
    public void onReceive(Context ctx, Intent in) {
        String action = in != null ? in.getAction() : null;
        if (action == null) {
            return;
        }

        switch (action) {
            case CoreService.ACTION_START: {
                // Distinguish "no extra" (service falls back to lastConfig) from
                // "extra supplied but unknown" (refuse outright).
                boolean supplied = in.hasExtra(CoreService.EXTRA_CONFIG);
                String raw = in.getStringExtra(CoreService.EXTRA_CONFIG);
                File file = resolveConfig(ctx, in);
                if (supplied && file == null) {
                    Core.get().logs().add("!! [ctl] 未知配置文件: " + raw);
                    return;
                }
                boolean ok = forward(ctx, CoreService.ACTION_START, file);
                reply(ok
                        ? "START 已转发" + (file != null ? " · " + file.getName() : "")
                        : "START 转发失败");
                return;
            }
            case CoreService.ACTION_STOP:
                // Do NOT route through the foreground service: an FGS that is
                // start-requested but never calls startForeground() would ANR.
                // stop() issues SIGTERM immediately, so the core is already dying even
                // if this short-lived thread is reclaimed; the running CoreService tears
                // itself down via its own Core.Listener once the core exits.
                new Thread(() -> Core.get().stop(), "ctl-stop").start();
                reply("STOP 已请求");
                return;
            case ACTION_STATUS: {
                String cfg = Core.get().configName();
                String error = Core.get().lastError();
                reply("running=" + Core.get().isRunning()
                        + " config=" + (cfg != null ? cfg : "-")
                        + " error=" + (error != null ? error : "-"));
                return;
            }
            case ACTION_LIST: {
                StringBuilder sb = new StringBuilder();
                for (File f : ConfigStore.list(ctx)) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(f.getName());
                }
                reply(sb.length() == 0 ? "-" : sb.toString());
                return;
            }
            case ACTION_SHUTDOWN:
                // Run inside the service so the platform does not reap the process
                // mid-teardown.
                reply(forward(ctx, CoreService.ACTION_SHUTDOWN, null)
                        ? "SHUTDOWN 已转发" : "SHUTDOWN 转发失败");
                return;
            default:
                Core.get().logs().add("!! [ctl] 未知动作: " + action);
        }
    }

    /**
     * Resolves the requested config to an entry that {@link ConfigStore#list} already
     * knows about. Accepts a bare file name or an absolute path, but never opens an
     * arbitrary path -- the receiver is exported, so this is a security boundary.
     *
     * @return the matching config, or {@code null} when no EXTRA_CONFIG was given or it
     *         does not resolve to a known config.
     */
    private static File resolveConfig(Context ctx, Intent in) {
        String raw = in.getStringExtra(CoreService.EXTRA_CONFIG);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        raw = raw.trim();
        for (File f : ConfigStore.list(ctx)) {
            if (raw.equals(f.getAbsolutePath()) || raw.equals(f.getName())) {
                return f;
            }
        }
        return null;
    }

    /**
     * Starts the service with the given action. Wrapped so a misbehaving platform call
     * can never crash this exported receiver.
     *
     * @return true when the platform accepted the start; a false reply must reach the
     *         caller verbatim, otherwise scripts would see a success that never happened.
     */
    private static boolean forward(Context ctx, String action, File file) {
        try {
            Intent svc = new Intent(ctx, CoreService.class).setAction(action);
            if (file != null) {
                svc.putExtra(CoreService.EXTRA_CONFIG, file.getAbsolutePath());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
            return true;
        } catch (Throwable t) {
            Core.get().logs().add("!! [ctl] 转发 " + action + " 失败: " + t);
            return false;
        }
    }

    /** Logs the reply into the app's log pane, and publishes it for ordered broadcasts. */
    private void reply(String text) {
        Core.get().logs().add("[ctl] " + text);
        if (isOrderedBroadcast()) {   // setResultData() is only valid for ordered broadcasts
            setResultData(text);
        }
    }
}
