package com.kkrainbow.easytier.mini;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the single easytier-core child process.
 *
 * <p>The Activity and the Service run in the same process, so a static singleton
 * is a safe and cheap way to share state between them.
 */
public final class Core {

    /** Address easytier-cli connects to. */
    public static final String RPC_PORTAL = "127.0.0.1:15888";

    /**
     * Address easytier-core binds its RPC portal to.
     *
     * <p>Deliberately NOT 127.0.0.1. Binding a concrete IP makes EasyTier resolve the
     * interface name for that IP and issue SO_BINDTODEVICE (BindDev::Auto, see
     * tunnel/common.rs:229-233 and :128-137), which an Android app UID may not do
     * (no CAP_NET_RAW) -- on the target's pre-5.10 kernel the core dies with
     * "failed to listen" and the whole process exits. An unspecified address makes
     * get_interface_name_by_ip return None, so the syscall is never attempted.
     *
     * <p>Binding 0.0.0.0 does not expose the portal on the LAN: the management RPC
     * defaults to a 127.0.0.0/8 + ::1/128 client whitelist
     * (easytier-core/src/management/rpc_server_hook.rs:16-18), so only local clients
     * can complete the handshake.
     */
    public static final String RPC_BIND = "0.0.0.0:15888";

    public interface Listener {
        void onCoreChanged();
    }

    private static final Core INSTANCE = new Core();

    public static Core get() {
        return INSTANCE;
    }

    private final Logs logs = new Logs(400);
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private Process proc;
    private String configName;
    private long startedAt;
    private String lastError;

    private Core() {
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fire() {
        for (Listener l : listeners) {
            l.onCoreChanged();
        }
    }

    public Logs logs() {
        return logs;
    }

    public boolean isRunning() {
        synchronized (lock) {
            return proc != null && proc.isAlive();
        }
    }

    public String configName() {
        synchronized (lock) {
            return configName;
        }
    }

    public long startedAt() {
        synchronized (lock) {
            return startedAt;
        }
    }

    public String lastError() {
        synchronized (lock) {
            return lastError;
        }
    }

    /** Blocking; call from a worker thread. Returns true when the process was spawned. */
    public boolean start(Context ctx, File srcConfig) {
        boolean ok;
        synchronized (lock) {
            if (proc != null && proc.isAlive()) {
                return true;
            }
            lastError = null;
            try {
                File cfg = ConfigStore.prepareRuntimeConfig(ctx, srcConfig);
                // Re-read the on-device copies on every launch: a replaced binary must take
                // effect on the next start, never by swapping it under a running process.
                Binaries.reload();
                File bin = Binaries.resolve(ctx, "easytier-core");

                List<String> cmd = new ArrayList<>();
                cmd.add(bin.getAbsolutePath());
                cmd.add("-c");
                cmd.add(cfg.getAbsolutePath());
                cmd.add("--rpc-portal");
                cmd.add(RPC_BIND);
                logs.add("$ " + joinCmd(cmd));

                Process p = spawnWithFallback(ctx, cmd, bin);

                proc = p;
                configName = srcConfig.getName();
                startedAt = System.currentTimeMillis();
                Thread t = new Thread(() -> pump(p), "core-log");
                t.setDaemon(true);
                t.start();
                logs.add("--- easytier-core 已启动 ---");
                ok = true;
            } catch (Throwable e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.toString();
                logs.add("!! 启动失败: " + e);
                proc = null;
                configName = null;
                startedAt = 0L;
                ok = false;
            }
        }
        fire();
        return ok;
    }

    /** Blocking; call from a worker thread. Sends SIGTERM first, then SIGKILL. */
    public void stop() {
        Process p;
        synchronized (lock) {
            p = proc;
            proc = null;
            configName = null;
            startedAt = 0L;
        }
        if (p == null) {
            return;
        }
        logs.add("--- 正在停止 easytier-core ... ---");
        p.destroy();
        if (!awaitExit(p, 5000)) {
            logs.add("--- 未响应 SIGTERM，强制结束 ---");
            p.destroyForcibly();
            awaitExit(p, 3000);
        }
        fire();
    }

    private void pump(Process p) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                logs.add(line);
            }
        } catch (IOException ignored) {
            // process gone
        }
        awaitExit(p, 3000);
        int code = -1;
        try {
            code = p.exitValue();
        } catch (IllegalThreadStateException ignored) {
            // still alive
        }
        logs.add("--- easytier-core 退出，code=" + code + " ---");
        synchronized (lock) {
            if (proc == p) {
                proc = null;
                configName = null;
                startedAt = 0L;
            }
        }
        fire();
    }

    /**
     * Spawns the core, retrying once with the factory copy when the resolved binary cannot
     * be executed at all. Exec from the app data dir is legal at targetSdk 28, but that is
     * not a promise on every ROM, and failing hard here would brick the app.
     *
     * <p>On the retry the command list is rewritten so the log shows what really ran.
     */
    private Process spawnWithFallback(Context ctx, List<String> cmd, File bin) throws IOException {
        try {
            return spawn(ctx, cmd);
        } catch (IOException first) {
            File pristineBin = Binaries.pristine(ctx, "easytier-core");
            if (bin.equals(pristineBin) || !pristineBin.canExecute()) {
                throw first;
            }
            logs.add("!! 无法执行 " + bin + "（" + first.getMessage() + "），回退到内置副本");
            cmd.set(0, pristineBin.getAbsolutePath());
            logs.add("$ " + joinCmd(cmd));
            return spawn(ctx, cmd);
        }
    }

    /** Spawns the core with the app's data dir as cwd and stderr merged into stdout. */
    private static Process spawn(Context ctx, List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(ctx.getFilesDir());
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private static boolean awaitExit(Process p, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (p.isAlive() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !p.isAlive();
    }

    private static String joinCmd(List<String> cmd) {
        StringBuilder b = new StringBuilder();
        for (String s : cmd) {
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(s.indexOf(' ') >= 0 ? "\"" + s + "\"" : s);
        }
        return b.toString();
    }
}
