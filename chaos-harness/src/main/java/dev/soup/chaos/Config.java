package dev.soup.chaos;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Command-line settings. Every flag has a default that matches the spec (chaos-verification). */
record Config(
        String mode,            // chaos | load
        String mutant,          // none | naive-claim | no-fencing | worker-clock-lease
        int jobs,
        double rate,            // creates per second in chaos mode
        long seed,
        int workers,
        Duration killEvery,
        Duration pauseEvery,
        Duration lease,
        Duration timeout,
        double faultWindow,     // faults continue until this share of jobs is terminal
        boolean fresh,
        boolean dryRun,
        String api,
        String dbUrl,
        String dbUser,
        String dbPassword,
        String runsDir,
        int loadRps,
        Duration loadDuration,
        String loadMix) {       // default (20/60/15/5) | creates

    static final String USAGE = """
            Usage: java -jar chaos-harness/target/chaos-harness.jar [flags]

              --mode chaos|load           chaos run (default) or load run for the verdict
              --mutant MODE               none | naive-claim | no-fencing | worker-clock-lease   (default none)
              --jobs N                    distinct idempotency keys                              (default 10000)
              --rate R                    creates per second                                     (default 15)
              --seed S                    seed for workload and fault schedule                   (default 1)
              --workers N                 worker containers                                      (default 8)
              --kill-every 2s             mean interval between SIGKILLs, 0 = no kills
              --pause-every 15s           mean interval between pauses, 0 = no pauses
              --lease 5s                  lease of the service (pauses last lease + 3 s)
              --timeout 15m               quiescence timeout
              --fault-window 0.95         keep injecting faults until this share of jobs is terminal
              --fresh true|false          docker compose down -v / up before the run             (default true)
              --dry-run                   print the plan summary and exit
              --api URL --db JDBC_URL     service endpoints (default localhost:8080, localhost:55432)
              --runs-dir DIR              where raw logs go                                      (default runs)
              --rps 100 --duration 5m     load mode rate and length
              --mix default|creates       load mode request mix: 20/60/15/5 or 100% creates
            """;

    static Config parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + a);
            }
            String key = a.substring(2);
            if (key.equals("dry-run") || key.equals("help")) {
                m.put(key, "true");
            } else if (i + 1 < args.length) {
                m.put(key, args[++i]);
            } else {
                throw new IllegalArgumentException("missing value for " + a);
            }
        }
        if (m.containsKey("help")) {
            return null;
        }
        return new Config(
                m.getOrDefault("mode", "chaos"),
                m.getOrDefault("mutant", "none"),
                Integer.parseInt(m.getOrDefault("jobs", "10000")),
                Double.parseDouble(m.getOrDefault("rate", "15")),
                Long.parseLong(m.getOrDefault("seed", "1")),
                Integer.parseInt(m.getOrDefault("workers", "8")),
                duration(m.getOrDefault("kill-every", "2s")),
                duration(m.getOrDefault("pause-every", "15s")),
                duration(m.getOrDefault("lease", "5s")),
                duration(m.getOrDefault("timeout", "15m")),
                Double.parseDouble(m.getOrDefault("fault-window", "0.95")),
                Boolean.parseBoolean(m.getOrDefault("fresh", "true")),
                Boolean.parseBoolean(m.getOrDefault("dry-run", "false")),
                m.getOrDefault("api", "http://localhost:8080"),
                m.getOrDefault("db", "jdbc:postgresql://localhost:55432/queue"),
                m.getOrDefault("db-user", "queue"),
                m.getOrDefault("db-password", "queue"),
                m.getOrDefault("runs-dir", "runs"),
                Integer.parseInt(m.getOrDefault("rps", "100")),
                duration(m.getOrDefault("duration", "5m")),
                m.getOrDefault("mix", "default"));
    }

    /** Accepts 500ms, 2s, 15m, 1h. */
    static Duration duration(String s) {
        s = s.trim();
        if (s.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2)));
        }
        long n = Long.parseLong(s.substring(0, s.length() - 1));
        return switch (s.charAt(s.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            default -> throw new IllegalArgumentException("bad duration " + s);
        };
    }

    /** Which invariants a mutant must break (spec chaos-verification, "Seeded-bug modes"). */
    String expectedCatch() {
        return switch (mutant) {
            case "naive-claim" -> "I9";
            case "no-fencing" -> "I2 or I5";
            case "worker-clock-lease" -> "I10";
            default -> "";
        };
    }

    String label() {
        return mode.equals("load") ? (loadMix.equals("creates") ? "load-creates" : "load") : mutant;
    }
}
