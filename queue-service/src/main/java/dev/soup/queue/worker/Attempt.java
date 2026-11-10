package dev.soup.queue.worker;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One claim of one job by this worker. Immutable identity (job, fencing token, execution row)
 * plus a stop flag. A new object per claim, so a late heartbeat or stop for an old attempt can never
 * touch the next job. No {@code Thread.interrupt} is used for this (design D6).
 */
final class Attempt {

    final UUID jobId;
    final long token;
    final long executionId;

    private final AtomicBoolean stop = new AtomicBoolean();
    private volatile long stopRequestedAtNanos;
    private volatile String stopReason;

    /** Monotonic time at which the last successful heartbeat (or the claim) was sent. */
    volatile long lastLeaseConfirmedNanos;

    Attempt(UUID jobId, long token, long executionId, long claimSentAtNanos) {
        this.jobId = jobId;
        this.token = token;
        this.executionId = executionId;
        this.lastLeaseConfirmedNanos = claimSentAtNanos;
    }

    void requestStop(String reason) {
        if (stop.compareAndSet(false, true)) {
            stopRequestedAtNanos = System.nanoTime();
            stopReason = reason;
        }
    }

    boolean stopRequested() {
        return stop.get();
    }

    long stopRequestedAtNanos() {
        return stopRequestedAtNanos;
    }

    String stopReason() {
        return stopReason;
    }
}
