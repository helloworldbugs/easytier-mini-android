package com.kkrainbow.easytier.mini;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Shows/hides the launcher icon by toggling the MAIN/LAUNCHER activity's enabled state.
 *
 * <p>There is no manifest attribute that does this at runtime, so the only mechanism is
 * {@link PackageManager#setComponentEnabledSetting}. A disabled component cannot be started
 * by anyone -- including this app -- so once the icon is hidden the UI is unreachable and
 * the only way back is the exported {@link ControlReceiver}:
 *
 * <pre>adb shell am broadcast -n &lt;pkg&gt;/.ControlReceiver -a &lt;pkg&gt;.action.SHOW_ICON</pre>
 *
 * <p>That recovery path is why this feature is safe to ship at all; {@link #restoreCommand}
 * produces the exact command for the user.
 */
final class Launcher {

    private Launcher() {
    }

    private static ComponentName component(Context ctx) {
        return new ComponentName(ctx, MainActivity.class);
    }

    /** True when the launcher entry is currently disabled. */
    static boolean isHidden(Context ctx) {
        int state = ctx.getPackageManager().getComponentEnabledSetting(component(ctx));
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
    }

    /**
     * Enables or disables the launcher entry.
     *
     * <p>{@code DONT_KILL_APP} is mandatory: a component-state change otherwise kills the
     * process, which would take the running core/service down with it just because the user
     * toggled an icon.
     */
    static void setHidden(Context ctx, boolean hidden) {
        ctx.getPackageManager().setComponentEnabledSetting(
                component(ctx),
                hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    /** The adb command that brings the icon back while it is hidden. */
    static String restoreCommand(Context ctx) {
        String pkg = ctx.getPackageName();
        return "adb shell am broadcast -n " + pkg + "/.ControlReceiver -a "
                + pkg + ".action.SHOW_ICON";
    }
}
