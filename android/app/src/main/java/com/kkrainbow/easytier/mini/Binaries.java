package com.kkrainbow.easytier.mini;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the easytier binaries so they can be replaced on the device.
 *
 * <p>Layout -- the first is the factory copy, the middle two belong to the user, the
 * last one is where we actually exec from:
 * <pre>
 *   nativeLibraryDir/lib&lt;base&gt;.so   factory copy the system extracted from the APK
 *   &lt;external files&gt;/bin/&lt;base&gt;      drop box, released on first run, user-editable
 *   /sdcard/EasyTier/bin/&lt;base&gt;      optional override, wins when present
 *   filesDir/bin/&lt;base&gt;              executed here
 * </pre>
 *
 * <p>Why not exec straight from the drop box: /sdcard is mounted {@code noexec}, so a
 * binary stored there can be read but never run. Exec from the app data dir is blocked
 * for apps targeting API 29+, but not for this app -- targetSdk 28 is what keeps the
 * on-device drop box workable at all.
 */
final class Binaries {

    /** base name -> resolved executable. Cleared by {@link #reload()} so a replaced file is picked up. */
    private static final ConcurrentHashMap<String, File> CACHE = new ConcurrentHashMap<>();

    private Binaries() {
    }

    static File resolve(Context ctx, String base) throws IOException {
        File cached = CACHE.get(base);
        if (cached != null && cached.isFile()) {
            return cached;
        }
        File file = prepare(ctx, base);
        CACHE.put(base, file);
        return file;
    }

    /**
     * Drops the cache so the next {@link #resolve} re-reads the on-device copies. Called
     * before every core launch: a replaced binary must take effect on the next start, and
     * must never be swapped out from under a running process. Also spares the 3-second
     * peer poll from touching the filesystem.
     */
    static void reload() {
        CACHE.clear();
    }

    /** The factory copy the system extracted from the APK's jniLibs. */
    static File pristine(Context ctx, String base) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "lib" + base + ".so");
    }

    private static File prepare(Context ctx, String base) throws IOException {
        File pristine = pristine(ctx, base);
        if (!pristine.isFile()) {
            throw new IOException("缺少内置二进制 " + pristine
                    + "（请先运行 tools/sync-binaries.sh 并重新打包）");
        }

        // getExternalFilesDir() is null when external storage is unavailable, and reading
        // the shared dir needs a granted storage permission -- both must degrade, not throw.
        File extDir = ctx.getExternalFilesDir(null);
        File dropBox = extDir == null ? null : new File(new File(extDir, "bin"), base);
        File override = new File(new File(new File(Environment.getExternalStorageDirectory(),
                "EasyTier"), "bin"), base);

        File src;
        String origin;
        if (override.isFile()) {
            src = override;
            origin = "SD 卡覆盖目录";
        } else if (dropBox != null && dropBox.isFile()) {
            src = dropBox;
            origin = "应用目录";
        } else if (dropBox != null) {
            // First run: put the originals where the user can find and replace them.
            mkdirs(dropBox.getParentFile());
            copy(pristine, dropBox);
            src = dropBox;
            origin = "内置（首次已释放到应用目录）";
        } else {
            src = pristine;
            origin = "内置（外部存储不可用）";
        }

        File exec = new File(new File(ctx.getFilesDir(), "bin"), base);
        if (!same(src, exec)) {
            mkdirs(exec.getParentFile());
            copy(src, exec);
        }
        if (!exec.canExecute() && !exec.setExecutable(true, false)) {
            throw new IOException("二进制不可执行 " + exec);
        }

        Core.get().logs().add("[bin] " + base + " <- " + origin + " (" + src.getAbsolutePath() + ")");
        return exec;
    }

    /**
     * Cheap idempotence check. {@link #copy} preserves the source's mtime, so equal length
     * plus equal stamp means equal content -- no hashing a 10 MB binary on every launch.
     */
    private static boolean same(File a, File b) {
        return b.isFile() && b.length() == a.length() && b.lastModified() == a.lastModified();
    }

    private static void mkdirs(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录 " + dir);
        }
    }

    private static void copy(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        dst.setReadable(true, true);
        dst.setLastModified(src.lastModified());
    }
}
