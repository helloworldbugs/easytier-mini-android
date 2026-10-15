package com.kkrainbow.easytier.mini;

import android.content.Context;
import android.content.SharedPreferences;

/** Typed wrapper over SharedPreferences. */
final class Prefs {
    private static final String FILE = "easytier_mini";
    private static final String K_AUTO_RUN = "auto_run";
    private static final String K_LAST_CONFIG = "last_config";
    private static final String K_SHUTDOWN = "shutdown_requested";

    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    boolean autoRun() {
        return sp.getBoolean(K_AUTO_RUN, false);
    }

    void setAutoRun(boolean value) {
        sp.edit().putBoolean(K_AUTO_RUN, value).apply();
    }

    String lastConfig() {
        return sp.getString(K_LAST_CONFIG, null);
    }

    void setLastConfig(String path) {
        sp.edit().putString(K_LAST_CONFIG, path).apply();
    }

    /** Set by the headless SHUTDOWN action so a sticky restart cannot undo it. */
    boolean shutdownRequested() {
        return sp.getBoolean(K_SHUTDOWN, false);
    }

    /**
     * Uses commit(), not apply(): this is written immediately before the process is
     * killed, so it must be durable before we die. Called from a worker thread.
     */
    void setShutdownRequested(boolean value) {
        sp.edit().putBoolean(K_SHUTDOWN, value).commit();
    }
}
