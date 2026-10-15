package com.kkrainbow.easytier.mini;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Config discovery, plus the no_tun runtime patch. */
final class ConfigStore {

    private static final long MAX_CONFIG_BYTES = 4L * 1024 * 1024;

    private ConfigStore() {
    }

    /** Directories scanned for configs, in priority order. */
    static List<File> searchDirs(Context ctx) {
        List<File> dirs = new ArrayList<>();
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) {
            dirs.add(ext);
        }
        dirs.add(new File(ctx.getFilesDir(), "configs"));
        dirs.add(new File(Environment.getExternalStorageDirectory(), "EasyTier"));
        return dirs;
    }

    /** Directories worth telling the user about (skips the app-private one). */
    static List<File> displayDirs(Context ctx) {
        List<File> dirs = new ArrayList<>();
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) {
            dirs.add(ext);
        }
        dirs.add(new File(Environment.getExternalStorageDirectory(), "EasyTier"));
        return dirs;
    }

    /** Where a picked config is imported to: the app's own dir, visible via adb/MTP. */
    static File importDir(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        return ext != null ? ext : new File(ctx.getFilesDir(), "configs");
    }

    static List<File> list(Context ctx) {
        List<File> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File dir : searchDirs(ctx)) {
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files) {
                if (!f.isFile()) {
                    continue;
                }
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".toml") && !name.endsWith(".conf")) {
                    continue;
                }
                if (seen.add(f.getAbsolutePath())) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /**
     * Writes a run-time copy with {@code no_tun = true} forced, leaving the user's
     * original file untouched.
     *
     * <p>Why: on Android easytier-core runs the "mobile" runtime host, which never
     * creates /dev/net/tun itself -- it waits for an in-process attach_tun_fd that a
     * child process cannot perform. Without no_tun the instance would wait forever.
     */
    static File prepareRuntimeConfig(Context ctx, File src) throws IOException {
        String patched = forceNoTun(read(src));
        File dir = new File(ctx.getFilesDir(), "run");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录 " + dir);
        }
        File dst = new File(dir, src.getName());
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(dst), StandardCharsets.UTF_8)) {
            w.write(patched);
        }
        return dst;
    }

    static String forceNoTun(String toml) {
        List<String> out = new ArrayList<>(Arrays.asList(toml.split("\r?\n", -1)));
        int flags = -1;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).trim().equals("[flags]")) {
                flags = i;
                break;
            }
        }
        if (flags < 0) {
            if (!out.isEmpty() && !out.get(out.size() - 1).trim().isEmpty()) {
                out.add("");
            }
            out.add("[flags]");
            out.add("no_tun = true");
            return join(out);
        }
        int end = out.size();
        for (int i = flags + 1; i < out.size(); i++) {
            if (out.get(i).trim().startsWith("[")) {
                end = i;
                break;
            }
        }
        for (int i = flags + 1; i < end; i++) {
            String line = out.get(i).trim();
            if (line.startsWith("no_tun") && line.contains("=")) {
                out.set(i, "no_tun = true");
                return join(out);
            }
        }
        out.add(flags + 1, "no_tun = true");
        return join(out);
    }

    /** Best-effort display name for a picked document, normalised to .toml/.conf. */
    static String displayName(Context ctx, Uri uri) {
        String name = null;
        try (Cursor c = ctx.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = c.getString(idx);
                }
            }
        } catch (Throwable ignored) {
            // fall through to the URI's last path segment
        }
        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.trim().isEmpty()) {
            name = "imported.toml";
        }
        name = new File(name).getName().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty()) {
            name = "imported.toml";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".toml") && !lower.endsWith(".conf")) {
            name = name + ".toml";
        }
        return name;
    }

    /**
     * Copies a picked document into the app's own config directory.
     *
     * <p>Writes to a {@code .part} file first and renames afterwards: picking a config
     * that already lives in that directory would otherwise read and truncate the same
     * file at once.
     */
    static File importConfig(Context ctx, Uri uri, String name) throws IOException {
        File dir = importDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录 " + dir);
        }
        File dst = new File(dir, name);
        File tmp = new File(dir, name + ".part");

        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("无法读取所选文件");
        }

        long total = 0;
        try (InputStream src = in; OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = src.read(buf)) > 0) {
                total += n;
                if (total > MAX_CONFIG_BYTES) {
                    throw new IOException("文件超过 " + (MAX_CONFIG_BYTES / 1024 / 1024)
                            + " MB，不像是配置文件");
                }
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (total == 0) {
            tmp.delete();
            throw new IOException("所选文件是空的");
        }

        if (!tmp.renameTo(dst)) {
            dst.delete();
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                throw new IOException("无法写入 " + dst);
            }
        }
        return dst;
    }

    private static String join(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            b.append(lines.get(i));
            if (i < lines.size() - 1) {
                b.append('\n');
            }
        }
        return b.toString();
    }

    private static String read(File f) throws IOException {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) {
                b.append(buf, 0, n);
            }
        }
        return b.toString();
    }
}
