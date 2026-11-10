package dev.soup.chaos;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The seeded workload: which keys are sent, which get duplicates, conflicting resends and cancels.
 * Same seed → same plan (spec: "Reproducible workload"). Timing and thread scheduling are not reproducible.
 */
final class Plan {

    enum Cancel { NONE, RANDOM, TARGETED }

    record KeyPlan(int index, String key, String payload, int concurrentDuplicates, List<Long> retryDelaysMs,
                   boolean conflictingResend, Cancel cancel, long cancelDelayMs) {
    }

    record Summary(int keys, int withDuplicates, int concurrentGroups, int delayedRetries, int conflicting,
                   int randomCancels, int targetedCancels) {
        @Override
        public String toString() {
            return String.format("keys=%d with-duplicates=%d (%.1f%%) concurrent-groups=%d delayed-retries=%d "
                            + "conflicting-resends=%d random-cancels=%d targeted-cancels=%d",
                    keys, withDuplicates, 100.0 * withDuplicates / Math.max(1, keys), concurrentGroups, delayedRetries,
                    conflicting, randomCancels, targetedCancels);
        }
    }

    private Plan() {
    }

    static List<KeyPlan> generate(long seed, int jobs) {
        SplittableRandom r = new SplittableRandom(seed);
        List<KeyPlan> plan = new ArrayList<>(jobs);
        for (int i = 0; i < jobs; i++) {
            String key = "s" + seed + "-" + i;
            String payload = String.format("{\"i\":%d,\"model\":\"m%d\",\"lr\":%.4f}", i, r.nextInt(10), r.nextDouble());
            int concurrent = 0;
            List<Long> retries = new ArrayList<>();
            double dup = r.nextDouble();
            if (dup < 0.15) {
                concurrent = 1 + r.nextInt(2);                  // 2 or 3 creates at the same instant
            } else if (dup < 0.32) {
                int n = 1 + r.nextInt(2);                       // 1 or 2 retries after 0.2–3 s
                for (int k = 0; k < n; k++) {
                    retries.add(200L + r.nextLong(2800));
                }
            }
            boolean conflicting = r.nextDouble() < 0.01;
            double c = r.nextDouble();
            Cancel cancel = c < 0.05 ? Cancel.RANDOM : c < 0.10 ? Cancel.TARGETED : Cancel.NONE;
            long cancelDelay = r.nextLong(3000);
            plan.add(new KeyPlan(i, key, payload, concurrent, List.copyOf(retries), conflicting, cancel, cancelDelay));
        }
        return plan;
    }

    static Summary summarize(List<KeyPlan> plan) {
        int dup = 0, groups = 0, retries = 0, conflict = 0, random = 0, targeted = 0;
        for (KeyPlan k : plan) {
            if (k.concurrentDuplicates() > 0 || !k.retryDelaysMs().isEmpty()) {
                dup++;
            }
            if (k.concurrentDuplicates() > 0) {
                groups++;
            }
            retries += k.retryDelaysMs().size();
            if (k.conflictingResend()) {
                conflict++;
            }
            if (k.cancel() == Cancel.RANDOM) {
                random++;
            }
            if (k.cancel() == Cancel.TARGETED) {
                targeted++;
            }
        }
        return new Summary(plan.size(), dup, groups, retries, conflict, random, targeted);
    }

    /** Different payload for the same key: must be answered 409 idempotency_conflict. */
    static String conflictingPayload(KeyPlan k) {
        return "{\"i\":" + k.index() + ",\"conflict\":true}";
    }
}
