package dev.soup.queue.job;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * DELIBERATELY BROKEN lease clock, used only with {@code QUEUE_MUTANT=worker-clock-lease}.
 * The lease expiry is computed from this process's wall clock plus a skew (one hour ahead by default)
 * instead of the database's {@code now()}. A killed worker's job then looks alive for an hour.
 * This is the only class in the service that reads the wall clock for a lease decision.
 */
final class WorkerClockLeaseMutant {

    private WorkerClockLeaseMutant() {
    }

    static Timestamp leaseExpiry(Duration skew, Duration lease) {
        return Timestamp.from(Instant.now().plus(skew).plus(lease));
    }
}
