package com.kkrainbow.easytier.mini;

import java.util.ArrayDeque;

/** Thread-safe bounded line buffer for captured process output. */
final class Logs {
    private final int cap;
    private final ArrayDeque<String> lines;

    Logs(int cap) {
        this.cap = cap;
        this.lines = new ArrayDeque<>(cap);
    }

    synchronized void add(String text) {
        if (text == null) {
            return;
        }
        for (String part : text.split("\r?\n", -1)) {
            lines.addLast(part);
            while (lines.size() > cap) {
                lines.removeFirst();
            }
        }
    }

    synchronized String text() {
        StringBuilder b = new StringBuilder();
        for (String line : lines) {
            b.append(line).append('\n');
        }
        return b.toString();
    }
}
