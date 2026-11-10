package dev.soup.queue.job;

import java.util.Locale;

/**
 * Seeded-bug modes (design D15). Each one breaks exactly one protection, so a chaos run in that mode
 * must fail. If it does not, the harness is blind to that bug. Off ({@link #NONE}) unless
 * {@code QUEUE_MUTANT} is set on both the API and the workers.
 */
public enum Mutant {
    /** Correct implementation. */
    NONE,
    /** Claim reads the oldest queued id without a lock, then updates it without re-checking the status. Caught by I9. */
    NAIVE_CLAIM,
    /** Heartbeat, complete and fail ignore status and token; the effects primary key is dropped. Caught by I2 / I5. */
    NO_FENCING,
    /** Lease expiry is computed from a worker wall clock skewed one hour ahead. Caught by I10. */
    WORKER_CLOCK_LEASE;

    public static Mutant parse(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    public String flag() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
