package dev.soup.queue.worker;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.soup.queue.QueueProperties;
import dev.soup.queue.job.ClaimedJob;
import dev.soup.queue.job.JobRepository;
import dev.soup.queue.job.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worker loop: one job at a time per process (design D12).
 * <pre>
 * claim → record execution → run with heartbeat + watchdog → complete | fail | give up
 * </pre>
 * Correctness does not depend on this class: every write it makes is fenced in SQL by the token.
 * This class decides how quickly a worker notices that it lost a job, and what it does about it.
 */
public class Worker {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private final String workerId;
    private final JobRepository repo;
    private final TrainingEmulator emulator;
    private final QueueProperties props;
    private final Exiter exiter;
    private final ScheduledExecutorService timers;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public Worker(String workerId, JobRepository repo, TrainingEmulator emulator, QueueProperties props, Exiter exiter) {
        this.workerId = workerId;
        this.repo = repo;
        this.emulator = emulator;
        this.props = props;
        this.exiter = exiter;
        this.timers = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "worker-timers");
            t.setDaemon(true);
            return t;
        });
    }

    public String workerId() {
        return workerId;
    }

    /** Stop claiming new jobs. The current attempt, if any, runs to its end. */
    public void requestShutdown() {
        running.set(false);
    }

    /**
     * Runs until {@link #requestShutdown()}. If the loop itself dies (a bug, an Error), heartbeats are
     * stopped at once so the lease can expire, and the process exits so Docker restarts it.
     */
    public void runLoop() {
        try {
            loop();
        } catch (Throwable t) {
            log.error("worker {} loop died, stopping heartbeats and exiting", workerId, t);
            timers.shutdownNow();
            exiter.exit(1);
            return;
        }
        timers.shutdownNow();
        log.info("worker {} stopped", workerId);
    }

    private void loop() throws InterruptedException {
        long backoffMs = props.idleBackoffMin().toMillis();
        while (running.get()) {
            long claimSentAt = System.nanoTime();
            Optional<ClaimedJob> claimed;
            try {
                claimed = repo.claim(workerId, props.lease());
            } catch (RuntimeException e) {
                log.warn("worker {} claim failed: {}", workerId, e.toString());
                claimed = Optional.empty();
            }
            if (claimed.isEmpty()) {
                sleepWithJitter(backoffMs);
                backoffMs = Math.min(backoffMs * 2, props.idleBackoffMax().toMillis());
                continue;
            }
            backoffMs = props.idleBackoffMin().toMillis(); // after a job: claim again immediately
            runAttempt(claimed.get(), claimSentAt);
        }
    }

    private void runAttempt(ClaimedJob job, long claimSentAt) {
        long executionId;
        try {
            executionId = repo.insertExecution(job.id(), job.leaseToken(), workerId);
        } catch (RuntimeException e) {
            // Spec: no execution without a record. The lease expires and the job is retried.
            log.warn("worker {} could not record execution of job {}, leaving it to lease expiry: {}",
                    workerId, job.id(), e.toString());
            return;
        }
        Attempt attempt = new Attempt(job.id(), job.leaseToken(), executionId, claimSentAt);
        log.info("worker {} claimed job {} token {} attempt {}", workerId, job.id(), job.leaseToken(), job.attempts());

        long hbMs = props.heartbeatInterval().toMillis();
        ScheduledFuture<?> heartbeat = timers.scheduleWithFixedDelay(() -> heartbeat(attempt), hbMs, hbMs,
                TimeUnit.MILLISECONDS);
        ScheduledFuture<?> watchdog = timers.scheduleWithFixedDelay(() -> watchdog(attempt), 100, 100,
                TimeUnit.MILLISECONDS);
        TrainingEmulator.Result result;
        try {
            result = emulator.run(job.id(), job.leaseToken(), job.payload(), attempt);
        } catch (RuntimeException e) {
            result = TrainingEmulator.Result.failed("execution threw " + e);
        } finally {
            heartbeat.cancel(false);
            watchdog.cancel(false);
        }
        finish(attempt, result);
    }

    /**
     * Runs on a timer thread. Never throws: an exception in a scheduled task would cancel the timer
     * silently and forever (design D6, review finding S2).
     */
    void heartbeat(Attempt attempt) {
        try {
            if (attempt.stopRequested()) {
                return;
            }
            long sentAt = System.nanoTime();
            boolean ok = repo.heartbeat(attempt.jobId, attempt.token, props.lease(), props.maxRuntime());
            if (ok) {
                attempt.lastLeaseConfirmedNanos = sentAt;
            } else {
                attempt.requestStop("lease lost (cancelled, reaped or max runtime exceeded)");
            }
        } catch (Throwable t) {
            log.warn("worker {} heartbeat for job {} failed, will retry: {}", workerId, attempt.jobId, t.toString());
        }
        // Self-fencing on the monotonic clock: if the lease could not be confirmed for almost a
        // whole lease period, assume it is gone, even if the database is unreachable.
        long sinceConfirmed = System.nanoTime() - attempt.lastLeaseConfirmedNanos;
        long limit = props.lease().minus(props.heartbeatInterval()).toNanos();
        if (sinceConfirmed > limit) {
            attempt.requestStop("self-fenced: lease not confirmed for " + sinceConfirmed / 1_000_000 + " ms");
        }
    }

    /** If an execution ignores a stop request for longer than the stop deadline, leave the process. */
    void watchdog(Attempt attempt) {
        try {
            if (attempt.stopRequested()
                    && System.nanoTime() - attempt.stopRequestedAtNanos() > props.stopDeadline().toNanos()) {
                log.error("worker {}: job {} ignored stop ({}) for over {}, exiting process",
                        workerId, attempt.jobId, attempt.stopReason(), props.stopDeadline());
                timers.shutdownNow();
                exiter.exit(1);
            }
        } catch (Throwable t) {
            log.error("worker {} watchdog failed", workerId, t);
        }
    }

    private void finish(Attempt attempt, TrainingEmulator.Result result) {
        try {
            if (attempt.stopRequested()) {
                // We were told (or decided) that the lease is gone: write nothing for the job.
                recordLost(attempt);
                return;
            }
            switch (result.outcome()) {
                case COMPLETED -> {
                    if (repo.complete(attempt.jobId, attempt.token, workerId, attempt.executionId)) {
                        log.info("worker {} job {} succeeded", workerId, attempt.jobId);
                    } else {
                        recordLost(attempt);
                    }
                }
                case FAILED -> {
                    if (repo.fail(attempt.jobId, attempt.token, attempt.executionId, result.error(), props.retryBase())) {
                        log.info("worker {} job {} attempt failed: {}", workerId, attempt.jobId, result.error());
                    } else {
                        recordLost(attempt);
                    }
                }
                case STOPPED -> recordLost(attempt);
            }
        } catch (RuntimeException e) {
            log.warn("worker {} could not finish job {}, lease will expire: {}", workerId, attempt.jobId, e.toString());
        }
    }

    /** The write was fenced or skipped. Record why, for the harness counters. */
    private void recordLost(Attempt attempt) {
        String outcome = repo.currentStatus(attempt.jobId)
                .filter(s -> s == JobStatus.CANCELLED)
                .map(s -> "cancelled")
                .orElse("fenced");
        repo.recordOutcome(attempt.executionId, outcome);
        log.info("worker {} job {} token {} gave up: {} ({})", workerId, attempt.jobId, attempt.token, outcome,
                attempt.stopReason() == null ? "commit fenced" : attempt.stopReason());
    }

    private static void sleepWithJitter(long ms) throws InterruptedException {
        long jitter = ms <= 1 ? 0 : ThreadLocalRandom.current().nextLong(ms / 5 + 1);
        Thread.sleep(ms + jitter);
    }
}
