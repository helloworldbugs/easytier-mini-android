package com.kkrainbow.easytier.mini;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Runs {@code easytier-cli ... peer -o json} and turns it into display rows. */
final class Cli {

    private Cli() {
    }

    static String execPeers(Context ctx, File cli, String rpcPortal) throws IOException {
        List<String> cmd = new ArrayList<>(Arrays.asList(
                cli.getAbsolutePath(), "-p", rpcPortal, "-o", "json", "peer"));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(ctx.getFilesDir());
        Process p = pb.start();

        drain(p.getErrorStream(), "cli-err");

        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }

        long deadline = System.currentTimeMillis() + 6000;
        while (p.isAlive() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (p.isAlive()) {
            p.destroyForcibly();
        }
        return out.toString();
    }

    private static void drain(final InputStream in, String name) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                while (r.readLine() != null) {
                    // discard: keeps the child from blocking on a full stderr pipe
                }
            } catch (IOException ignored) {
                // nothing to do
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    static List<String> parsePeers(String stdout) {
        List<String> rows = new ArrayList<>();
        try {
            int a = stdout.indexOf('[');
            int b = stdout.lastIndexOf(']');
            if (a < 0 || b <= a) {
                return rows;
            }
            JSONArray arr = new JSONArray(stdout.substring(a, b + 1));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                JSONArray inner = o.optJSONArray("result");
                if (inner != null) {
                    for (int j = 0; j < inner.length(); j++) {
                        addPeer(rows, inner.optJSONObject(j));
                    }
                } else {
                    addPeer(rows, o);
                }
            }
        } catch (Throwable t) {
            rows.add("无法解析 peer 输出: " + t);
        }
        return rows;
    }

    private static void addPeer(List<String> rows, JSONObject o) {
        if (o == null) {
            return;
        }
        String host = o.optString("hostname", "?");
        String ip = o.optString("ipv4", o.optString("cidr", "?"));
        String cost = o.optString("cost", "?");
        String lat = o.optString("lat_ms", "?");
        String loss = o.optString("loss_rate", "?");
        String tunnel = o.optString("tunnel_proto", "?");
        String nat = o.optString("nat_type", "?");
        String version = o.optString("version", "");

        StringBuilder b = new StringBuilder();
        b.append(host).append("  ").append(ip).append('\n');
        b.append("cost ").append(cost)
                .append("   延迟 ").append(lat).append(" ms")
                .append("   丢包 ").append(loss).append('\n');
        b.append("隧道 ").append(tunnel).append("   NAT ").append(nat);
        if (!version.isEmpty()) {
            b.append("   v").append(version);
        }
        rows.add(b.toString());
    }
}
