package dev.soup.chaos;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Everything the harness prints goes to stdout and to the raw run log. The log file is created once
 * and never overwritten: a name clash gets a numeric suffix (spec: "Raw output is kept for every run").
 */
final class Out {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final long START = System.nanoTime();
    private static PrintWriter file;
    private static Path path;

    private Out() {
    }

    static Path open(String runsDir, String label, long seed) throws IOException {
        Path dir = Path.of(runsDir);
        Files.createDirectories(dir);
        String base = FILE_TS.format(Instant.now()) + "-" + label + "-seed" + seed;
        Path p = dir.resolve(base + ".log");
        for (int i = 2; Files.exists(p); i++) {
            p = dir.resolve(base + "-" + i + ".log");
        }
        file = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW), true);
        path = p;
        return p;
    }

    static Path path() {
        return path;
    }

    static synchronized void line(String s) {
        String stamped = String.format("[%7.1fs] %s", (System.nanoTime() - START) / 1e9, s);
        System.out.println(stamped);
        if (file != null) {
            file.println(stamped);
        }
    }

    static void line(String fmt, Object... args) {
        line(String.format(fmt, args));
    }

    static synchronized void raw(String s) {
        System.out.println(s);
        if (file != null) {
            file.println(s);
        }
    }

    static void section(String title) {
        raw("");
        raw("==== " + title + " " + "=".repeat(Math.max(0, 70 - title.length())));
    }

    static double elapsedSeconds() {
        return (System.nanoTime() - START) / 1e9;
    }

    static String human(Duration d) {
        long s = d.toSeconds();
        return s >= 60 ? (s / 60) + "m" + (s % 60) + "s" : d.toMillis() + "ms";
    }

    static synchronized void close() {
        if (file != null) {
            file.close();
        }
    }
}
