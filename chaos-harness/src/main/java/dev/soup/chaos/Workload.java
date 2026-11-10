package dev.soup.chaos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends the plan at a fixed pace (default 15 creates/s, below the drain rate, so faults overlap with
 * execution for the whole run). Duplicates, conflicting resends and random cancels follow each key on
 * its own virtual thread. Targeted cancels go to jobs the harness sees in the database as running.
 */
final class Workload {

    private final Config cfg;
    private final Api api;
    private final ClientLog log;
    private final Db db;
    private final List<Plan.KeyPlan> plan;
    private final ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, String> targeted = new ConcurrentHashMap<>(); // jobId -> key
    private final AtomicBoolean stopTargeting = new AtomicBoolean();
    final AtomicInteger submitted = new AtomicInteger();
    final AtomicInteger targetedSent = new AtomicInteger();
    volatile boolean submissionsDone;

    Workload(Config cfg, Api api, ClientLog log, Db db, List<Plan.KeyPlan> plan) {
        this.cfg = cfg;
        this.api = api;
        this.log = log;
        this.db = db;
        this.plan = plan;
    }

    /** Blocks until every key and its follow-ups (duplicates, resends, cancels) are done. */
    void run() throws Exception {
        Thread targeter = Thread.ofPlatform().name("targeted-cancels").daemon().start(this::targetedCancels);
        long start = System.nanoTime();
        double intervalNanos = 1e9 / cfg.rate();
        List<Future<?>> tasks = new ArrayList<>(plan.size());
        for (Plan.KeyPlan k : plan) {
            long due = start + (long) (k.index() * intervalNanos);
            long wait = due - System.nanoTime();
            if (wait > 0) {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            }
            tasks.add(vthreads.submit(() -> handle(k)));
            submitted.incrementAndGet();
        }
        submissionsDone = true;
        for (Future<?> f : tasks) {
            f.get();
        }
        // The targeter keeps running while jobs still execute; Main stops it after the fault window.
    }

    void stopTargeting() {
        stopTargeting.set(true);
    }

    private Void handle(Plan.KeyPlan k) throws Exception {
        String id = null;
        if (k.concurrentDuplicates() > 0) {
            int n = 1 + k.concurrentDuplicates();
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Api.Response>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(vthreads.submit(() -> {
                    go.await();
                    return api.create(k.key(), k.payload());
                }));
            }
            go.countDown();
            for (Future<Api.Response> f : fs) {
                Api.Response r = f.get();
                log.add("create", k.key(), r, true);
                id = id != null ? id : acceptedId(r);
            }
        } else {
            Api.Response r = api.create(k.key(), k.payload());
            log.add("create", k.key(), r, false);
            id = acceptedId(r);
        }
        if (k.conflictingResend()) {
            log.add("conflict", k.key(), api.create(k.key(), Plan.conflictingPayload(k)), false);
        }
        for (long delay : k.retryDelaysMs()) {
            Thread.sleep(delay);
            Api.Response r = api.create(k.key(), k.payload());
            log.add("resend", k.key(), r, false);
            id = id != null ? id : acceptedId(r);
        }
        if (id == null) {
            return null; // every create timed out: nothing to cancel
        }
        if (k.cancel() == Plan.Cancel.RANDOM) {
            Thread.sleep(k.cancelDelayMs());
            log.add("cancel", k.key(), api.cancel(id), false);
        } else if (k.cancel() == Plan.Cancel.TARGETED) {
            targeted.put(id, k.key());
        }
        return null;
    }

    private static String acceptedId(Api.Response r) {
        return r.code() == 200 || r.code() == 201 ? r.jobId() : null;
    }

    /** Every 200 ms: which targeted jobs are running right now? Cancel them 0–400 ms later. */
    private void targetedCancels() {
        while (!stopTargeting.get()) {
            try {
                Thread.sleep(200);
                if (targeted.isEmpty()) {
                    continue;
                }
                for (String id : db.strings("SELECT id::text FROM jobs WHERE status = 'running'")) {
                    String key = targeted.remove(id);
                    if (key != null) {
                        long delay = ThreadLocalRandom.current().nextLong(401);
                        targetedSent.incrementAndGet();
                        vthreads.submit(() -> {
                            Thread.sleep(delay);
                            log.add("cancel", key, api.cancel(id), false);
                            return null;
                        });
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                Out.line("targeted cancels: database read failed, retrying: %s", e.getMessage());
            }
        }
    }
}
