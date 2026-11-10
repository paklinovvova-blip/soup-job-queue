package dev.soup.chaos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The invariants of spec chaos-verification. I1–I9 are checked once after quiescence, I10 is sampled
 * every second during the run. Each check returns the number of offending jobs and up to 20 ids.
 */
final class Invariants {

    record Check(String id, String name, boolean passed, int offending, List<String> sample) {
        static Check of(String id, String name, Set<String> offenders) {
            List<String> sample = offenders.stream().limit(20).toList();
            return new Check(id, name, offenders.isEmpty(), offenders.size(), sample);
        }
    }

    /** Legal edges of the state machine with their token change (I9, design D4/D7). */
    static final String ILLEGAL_TRANSITIONS = """
            SELECT DISTINCT job_id::text FROM job_transitions
             WHERE NOT (
                   (old_status = 'queued'  AND new_status = 'running'   AND new_token = old_token + 1)
                OR (old_status = 'running' AND new_status = 'queued'    AND new_token = old_token + 1)
                OR (old_status = 'running' AND new_status = 'failed'    AND new_token = old_token + 1)
                OR (old_status = 'running' AND new_status = 'succeeded' AND new_token = old_token)
                OR (old_status IN ('queued', 'running') AND new_status = 'cancelled' AND new_token = old_token + 1))
            """;

    private final Db db;
    private final List<ClientLog.Entry> log;
    private final Set<String> submittedKeys;

    Invariants(Db db, List<ClientLog.Entry> log, Set<String> submittedKeys) {
        this.db = db;
        this.log = log;
        this.submittedKeys = submittedKeys;
    }

    List<Check> afterQuiescence() {
        Map<String, String> idByKey = db.pairs("SELECT idempotency_key, id::text FROM jobs");
        Map<String, String> statusById = db.pairs("SELECT id::text, status FROM jobs");
        Map<String, String> effectsById = db.pairs("SELECT job_id::text, count(*)::text FROM effects GROUP BY 1");
        List<Check> checks = new ArrayList<>();
        checks.add(i1(idByKey));
        checks.add(Check.of("I2", "at most one effect per job",
                set(db.strings("SELECT job_id::text FROM effects GROUP BY job_id HAVING count(*) > 1"))));
        checks.add(Check.of("I3", "succeeded <=> exactly one effect; failed and cancelled have none", set(db.strings("""
                SELECT j.id::text FROM jobs j
                  LEFT JOIN (SELECT job_id, count(*) AS n FROM effects GROUP BY job_id) e ON e.job_id = j.id
                 WHERE (j.status = 'succeeded' AND coalesce(e.n, 0) <> 1)
                    OR (j.status <> 'succeeded' AND coalesce(e.n, 0) > 0)
                """))));
        checks.add(Check.of("I4", "no job left queued or running",
                set(db.strings("SELECT id::text FROM jobs WHERE status IN ('queued', 'running')"))));
        checks.add(i5(statusById, effectsById));
        checks.add(i6(idByKey));
        Set<String> i7 = new LinkedHashSet<>();
        i7.addAll(db.strings("""
                SELECT e.job_id::text FROM effects e JOIN jobs j ON j.id = e.job_id WHERE e.lease_token <> j.lease_token
                """));
        i7.addAll(db.strings("""
                SELECT e.job_id::text FROM effects e
                 WHERE NOT EXISTS (SELECT 1 FROM executions x
                                    WHERE x.job_id = e.job_id AND x.lease_token = e.lease_token AND x.outcome = 'succeeded')
                """));
        i7.addAll(db.strings("""
                SELECT DISTINCT j.id::text FROM jobs j JOIN executions x ON x.job_id = j.id
                 WHERE j.status = 'cancelled' AND x.lease_token >= j.lease_token
                """));
        checks.add(Check.of("I7", "effect written by the final owner's recorded run; cancelled jobs only ran with older tokens", i7));
        checks.add(Check.of("I8", "attempts <= max; failed jobs used all attempts", set(db.strings("""
                SELECT id::text FROM jobs WHERE attempts > max_attempts OR (status = 'failed' AND attempts <> max_attempts)
                """))));
        checks.add(Check.of("I9", "audit log has only legal transitions", set(db.strings(ILLEGAL_TRANSITIONS))));
        return checks;
    }

    /** I1: every accepted key has exactly one job, and no job exists for a key we never sent. */
    private Check i1(Map<String, String> idByKey) {
        Set<String> accepted = new HashSet<>();
        for (ClientLog.Entry e : log) {
            if ((e.type().equals("create") || e.type().equals("resend")) && (e.code() == 200 || e.code() == 201)) {
                accepted.add(e.key());
            }
        }
        Set<String> offenders = new LinkedHashSet<>();
        for (String key : accepted) {
            if (!idByKey.containsKey(key)) {
                offenders.add("lost:" + key);
            }
        }
        for (String key : idByKey.keySet()) {
            if (!submittedKeys.contains(key)) {
                offenders.add("unknown-key:" + key);
            }
        }
        return Check.of("I1", "no accepted job lost, no job without a submitted key", offenders);
    }

    /** I5: what a cancel response said is what the job ended as. */
    private Check i5(Map<String, String> statusById, Map<String, String> effectsById) {
        Set<String> offenders = new LinkedHashSet<>();
        for (ClientLog.Entry e : log) {
            if (!e.type().equals("cancel") || e.code() < 0 || e.code() >= 500) {
                continue; // unknown outcome: neither a pass nor a fail
            }
            String id = e.jobId();
            String finalStatus = id == null ? null : statusById.get(id);
            if (e.code() == 200) {
                if (!"cancelled".equals(e.status()) || !"cancelled".equals(finalStatus) || effectsById.containsKey(id)) {
                    offenders.add(String.valueOf(id));
                }
            } else if (e.code() == 409 && "job_finished".equals(e.error())) {
                if (finalStatus == null || !finalStatus.equals(e.status())) {
                    offenders.add(String.valueOf(id));
                }
            } else {
                offenders.add(id + ":http" + e.code()); // e.g. 404 for a job the API itself returned
            }
        }
        return Check.of("I5", "cancel responses match final states", offenders);
    }

    /** I6: all answers for one key name the same job, which is the job in the database; conflicts are 409. */
    private Check i6(Map<String, String> idByKey) {
        Map<String, Set<String>> idsByKey = new HashMap<>();
        Set<String> offenders = new LinkedHashSet<>();
        for (ClientLog.Entry e : log) {
            boolean accepted = (e.type().equals("create") || e.type().equals("resend")) && (e.code() == 200 || e.code() == 201);
            boolean conflict = e.type().equals("conflict") && e.code() == 409;
            if (e.type().equals("conflict") && (e.code() == 200 || e.code() == 201)) {
                offenders.add("conflict-accepted:" + e.key());
            }
            if ((accepted || conflict) && e.jobId() != null) {
                idsByKey.computeIfAbsent(e.key(), k -> new HashSet<>()).add(e.jobId());
            }
        }
        idsByKey.forEach((key, ids) -> {
            if (ids.size() != 1 || !ids.contains(idByKey.get(key))) {
                offenders.add(key);
            }
        });
        return Check.of("I6", "one job id per key across all responses", offenders);
    }

    private static Set<String> set(List<String> l) {
        return new LinkedHashSet<>(l);
    }

    /** I10, sampled once per second while the run is going. */
    static final class LeaseHorizon {
        private final Db db;
        private final Duration lease;
        private final Set<String> offenders = ConcurrentHashMap.newKeySet();
        private final AtomicLong samples = new AtomicLong();
        private final AtomicLong failedSamples = new AtomicLong();
        private volatile boolean running = true;
        private Thread thread;

        LeaseHorizon(Db db, Duration lease) {
            this.db = db;
            this.lease = lease;
        }

        static String query(Duration lease) {
            return "SELECT id::text FROM jobs WHERE status = 'running' AND lease_expires_at > now() + interval '"
                    + (2 * lease.toMillis()) + " milliseconds'";
        }

        void start() {
            thread = Thread.ofPlatform().name("i10-sampler").daemon().start(() -> {
                while (running) {
                    try {
                        offenders.addAll(db.strings(query(lease)));
                        samples.incrementAndGet();
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    } catch (RuntimeException e) {
                        failedSamples.incrementAndGet();
                    }
                }
            });
        }

        void stop() throws InterruptedException {
            running = false;
            if (thread != null) {
                thread.interrupt();
                thread.join(5000);
            }
        }

        Check result() {
            return Check.of("I10", "no lease further than now() + 2 x lease (" + samples.get() + " samples, "
                    + failedSamples.get() + " failed)", new LinkedHashSet<>(offenders));
        }
    }
}
