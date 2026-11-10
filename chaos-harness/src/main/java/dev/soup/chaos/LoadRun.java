package dev.soup.chaos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Load run for the SHIP / DON'T SHIP verdict (spec: "Load run with pre-registered criteria").
 * Open loop at a fixed rate: requests are sent on schedule whether or not earlier ones returned,
 * so a slow server cannot slow the load down and hide its own latency.
 * Mix and criteria are fixed in the spec before the run: 20% create, 60% get, 15% list, 5% cancel;
 * SHIP if p99 < 200 ms, errors < 0.1% and queue depth does not grow over the last 3 minutes.
 */
final class LoadRun {

    record Sample(double atSeconds, long queued, long running) {
    }

    private LoadRun() {
    }

    static int run(Config cfg, Db db) throws Exception {
        Api api = new Api(cfg.api());
        boolean createsOnly = cfg.loadMix().equals("creates");
        Out.section("LOAD");
        Out.line("open loop: %d requests/s for %s, mix %s", cfg.loadRps(), Out.human(cfg.loadDuration()),
                createsOnly ? "100% create" : "20% create / 60% get / 15% list / 5% cancel");

        List<String> ids = Collections.synchronizedList(new ArrayList<>());
        Map<String, ConcurrentLinkedQueue<Long>> latencies = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> errors = new ConcurrentHashMap<>();
        AtomicInteger seq = new AtomicInteger();
        List<Sample> depth = Collections.synchronizedList(new ArrayList<>());
        long start = System.nanoTime();

        Thread sampler = Thread.ofPlatform().daemon().start(() -> {
            while (true) {
                try {
                    long q = db.count("SELECT count(*) FROM jobs WHERE status = 'queued'");
                    long r = db.count("SELECT count(*) FROM jobs WHERE status = 'running'");
                    depth.add(new Sample((System.nanoTime() - start) / 1e9, q, r));
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException ignored) {
                    // a missed sample is visible as a gap
                }
            }
        });

        long total = cfg.loadRps() * cfg.loadDuration().toSeconds();
        double intervalNanos = 1e9 / cfg.loadRps();
        try (ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long n = 0; n < total; n++) {
                long wait = start + (long) (n * intervalNanos) - System.nanoTime();
                if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                }
                String type = pick(createsOnly, ids.isEmpty());
                vthreads.submit(() -> {
                    Api.Response r = switch (type) {
                        case "create" -> api.create("load-" + start + "-" + seq.incrementAndGet(),
                                "{\"load\":true,\"n\":" + seq.get() + "}");
                        case "get" -> api.get(randomId(ids));
                        case "list" -> api.list("?limit=20");
                        default -> api.cancel(randomId(ids));
                    };
                    latencies.computeIfAbsent(type, k -> new ConcurrentLinkedQueue<>()).add(r.latencyNanos());
                    if (r.code() < 0 || r.code() >= 500) {
                        errors.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
                    }
                    if (type.equals("create") && r.code() == 201 && r.jobId() != null) {
                        ids.add(r.jobId());
                    }
                });
            }
        } // waits for in-flight requests (each has a 15 s timeout)
        double seconds = (System.nanoTime() - start) / 1e9;
        Thread.sleep(5000); // one more depth sample after the load stops
        sampler.interrupt();

        Out.section("LOAD RESULTS");
        long all = 0;
        long allErrors = 0;
        List<Long> merged = new ArrayList<>();
        for (String type : List.of("create", "get", "list", "cancel")) {
            ConcurrentLinkedQueue<Long> q = latencies.get(type);
            if (q == null) {
                continue;
            }
            List<Long> l = new ArrayList<>(q);
            merged.addAll(l);
            int err = errors.getOrDefault(type, new AtomicInteger()).get();
            all += l.size();
            allErrors += err;
            Out.raw(String.format("%-7s n=%-6d p50=%6.1fms p95=%6.1fms p99=%6.1fms max=%7.1fms errors=%d",
                    type, l.size(), pct(l, 50), pct(l, 95), pct(l, 99), pct(l, 100), err));
        }
        double p99 = pct(merged, 99);
        double errorRate = all == 0 ? 0 : (double) allErrors / all;
        Out.raw(String.format("all     n=%-6d p50=%6.1fms p95=%6.1fms p99=%6.1fms errors=%d (%.3f%%) achieved=%.1f req/s",
                all, pct(merged, 50), pct(merged, 95), p99, allErrors, 100 * errorRate, all / seconds));

        Out.section("QUEUE DEPTH (every 5 s)");
        for (Sample s : depth) {
            Out.raw(String.format("%7.1fs queued=%-7d running=%d", s.atSeconds(), s.queued(), s.running()));
        }

        Out.section("VERDICT AGAINST PRE-REGISTERED CRITERIA");
        long creates = latencies.getOrDefault("create", new ConcurrentLinkedQueue<>()).size();
        double createRate = creates / seconds;
        double growth = depthGrowthPerSecond(depth, seconds, 180);
        double allowedGrowth = Math.max(20.0 / 180, 0.05 * createRate);
        boolean latencyOk = p99 < 200;
        boolean errorsOk = errorRate < 0.001;
        boolean depthOk = growth <= allowedGrowth;
        Out.raw(String.format("p99 latency < 200 ms            %-4s (%.1f ms)", ok(latencyOk), p99));
        Out.raw(String.format("errors (5xx + timeouts) < 0.1%%  %-4s (%.3f%%)", ok(errorsOk), 100 * errorRate));
        Out.raw(String.format("queue depth not growing (3 min) %-4s (%.2f jobs/s growth, allowed %.2f; creates %.1f/s)",
                ok(depthOk), growth, allowedGrowth, createRate));
        boolean ship = latencyOk && errorsOk && depthOk;
        Out.raw(ship ? "SHIP at this load and mix" : "DON'T SHIP at this load and mix");
        return 0; // a measurement, not a pass/fail test: the verdict is in the log
    }

    private static String pick(boolean createsOnly, boolean noIdsYet) {
        if (createsOnly || noIdsYet) {
            return "create";
        }
        double r = ThreadLocalRandom.current().nextDouble();
        return r < 0.20 ? "create" : r < 0.80 ? "get" : r < 0.95 ? "list" : "cancel";
    }

    private static String randomId(List<String> ids) {
        synchronized (ids) {
            return ids.get(ThreadLocalRandom.current().nextInt(ids.size()));
        }
    }

    private static double pct(List<Long> nanos, double p) {
        if (nanos.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1))) / 1e6;
    }

    /** Growth of (queued + running) over the last {@code windowSeconds} of the load, in jobs per second. */
    static double depthGrowthPerSecond(List<Sample> depth, double loadSeconds, double windowSeconds) {
        List<Sample> during = depth.stream().filter(s -> s.atSeconds() <= loadSeconds + 0.5).toList();
        if (during.size() < 2) {
            return 0;
        }
        Sample last = during.get(during.size() - 1);
        Sample first = during.get(0);
        for (Sample s : during) {
            if (s.atSeconds() >= last.atSeconds() - windowSeconds) {
                first = s;
                break;
            }
        }
        double dt = last.atSeconds() - first.atSeconds();
        return dt <= 0 ? 0 : ((last.queued() + last.running()) - (first.queued() + first.running())) / dt;
    }

    private static String ok(boolean b) {
        return b ? "OK" : "FAIL";
    }
}
