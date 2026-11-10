package dev.soup.queue.worker;

import static dev.soup.queue.job.TestDb.JDBC;
import static dev.soup.queue.job.TestDb.REPO;
import static dev.soup.queue.job.TestDb.TX;
import static dev.soup.queue.job.TestDb.count;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import dev.soup.queue.QueueProperties;
import dev.soup.queue.job.ClaimedJob;
import dev.soup.queue.job.Job;
import dev.soup.queue.job.JobRepository;
import dev.soup.queue.job.JobStatus;
import dev.soup.queue.job.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tasks 4.1 and 4.3–4.6: the worker loop against a real Postgres, with real timing. */
class WorkerTest {

    private final List<Worker> started = new ArrayList<>();
    private final List<Reaper> reapers = new ArrayList<>();

    @BeforeEach
    void clean() {
        TestDb.clean();
    }

    @AfterEach
    void stopAll() {
        started.forEach(Worker::requestShutdown);
        reapers.forEach(Reaper::stop);
    }

    // ----------------------------------------------------------------- helpers

    static final class Config {
        Duration lease = Duration.ofSeconds(5);
        Duration heartbeat = Duration.ofSeconds(1);
        Duration reaper = Duration.ofSeconds(1);
        Duration maxRuntime = Duration.ofSeconds(60);
        Duration stopDeadline = Duration.ofSeconds(5);
        Duration retryBase = Duration.ofMillis(20);
        Duration minDuration = Duration.ofMillis(1);
        Duration maxDuration = Duration.ofMillis(5);
        double failure = 0.0;
        boolean hooks = false;

        QueueProperties build() {
            return new QueueProperties("none", lease, heartbeat, reaper, 100, maxRuntime, retryBase, stopDeadline,
                    Duration.ofMillis(10), Duration.ofMillis(50), hooks,
                    new QueueProperties.Emulator(minDuration, maxDuration, failure));
        }
    }

    /** Records exits instead of leaving the JVM. A halt aborts the worker thread like a crash would. */
    static final class FakeExiter implements Exiter {
        final AtomicInteger exits = new AtomicInteger();
        final AtomicInteger halts = new AtomicInteger();

        @Override
        public void exit(int code) {
            exits.incrementAndGet();
        }

        @Override
        public void halt(int code) {
            halts.incrementAndGet();
            throw new SimulatedCrash();
        }
    }

    static final class SimulatedCrash extends Error {
    }

    private Worker start(Config cfg, JobRepository repo, Exiter exiter) {
        QueueProperties props = cfg.build();
        Worker worker = new Worker(WorkerIdentity.newWorkerId(), repo,
                new TrainingEmulator(props.emulator(), props.testHooks(), exiter), props, exiter);
        Thread t = new Thread(worker::runLoop, "test-worker");
        t.setDaemon(true);
        t.start();
        started.add(worker);
        return worker;
    }

    private Reaper startReaper(Config cfg, JobRepository repo) {
        Reaper reaper = new Reaper(repo, cfg.build());
        reaper.start();
        reapers.add(reaper);
        return reaper;
    }

    private static Job job(String key, String payload) {
        return REPO.insertIfAbsent(UUID.randomUUID(), key, "h", payload).orElseThrow();
    }

    private static long waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long start = System.nanoTime();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - start > timeout.toNanos()) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(20);
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static JobStatus status(UUID id) {
        return REPO.currentStatus(id).orElseThrow();
    }

    // --------------------------------------------------------------------- 4.1

    @Test
    void fourWorkersFinishTwoHundredJobs() throws Exception {
        for (int i = 0; i < 200; i++) {
            job("k" + i, "{}");
        }
        Config cfg = new Config();
        cfg.failure = 0.05;
        for (int i = 0; i < 4; i++) {
            start(cfg, REPO, new FakeExiter());
        }
        waitUntil(() -> count("SELECT count(*) FROM jobs WHERE status IN ('queued', 'running')") == 0,
                Duration.ofSeconds(60));

        assertThat(count("SELECT count(*) FROM effects"))
                .isEqualTo(count("SELECT count(*) FROM jobs WHERE status = 'succeeded'"));
        assertThat(count("SELECT count(*) FROM jobs WHERE status = 'failed' AND attempts <> max_attempts")).isZero();
        assertThat(count("SELECT count(DISTINCT worker_id) FROM executions")).isEqualTo(4);
        assertThat(TestDb.illegalTransitions()).isZero();
    }

    @Test
    void everyProcessStartGetsANewIdWithTheSameHostPrefix() {
        String a = WorkerIdentity.newWorkerId();
        String b = WorkerIdentity.newWorkerId();
        assertThat(a).isNotEqualTo(b);
        assertThat(a.substring(0, a.lastIndexOf('-'))).isEqualTo(b.substring(0, b.lastIndexOf('-')));
        assertThat(a.substring(a.lastIndexOf('-') + 1)).hasSize(8);
    }

    // --------------------------------------------------------------------- 4.3

    @Test
    void cancelDuringExecutionStopsTheWorkerWithinOneHeartbeat() throws Exception {
        Job job = job("long", "{}");
        Config cfg = new Config();
        cfg.minDuration = cfg.maxDuration = Duration.ofSeconds(10);
        start(cfg, REPO, new FakeExiter());
        waitUntil(() -> count("SELECT count(*) FROM executions WHERE job_id = ?", job.id()) == 1, Duration.ofSeconds(5));

        REPO.cancel(job.id());
        long stoppedAfterMs = waitUntil(
                () -> count("SELECT count(*) FROM executions WHERE job_id = ? AND outcome = 'cancelled'", job.id()) == 1,
                Duration.ofSeconds(3));

        assertThat(stoppedAfterMs).isLessThan(1500);
        assertThat(status(job.id())).isEqualTo(JobStatus.CANCELLED);
        assertThat(count("SELECT count(*) FROM effects")).isZero();
    }

    @Test
    void workerSelfFencesWhenHeartbeatsKeepFailing() throws Exception {
        JobRepository brokenHeartbeat = new JobRepository(JDBC, TX) {
            @Override
            public boolean heartbeat(UUID jobId, long token, Duration lease, Duration maxRuntime) {
                throw new org.springframework.dao.DataAccessResourceFailureException("simulated: database unreachable");
            }
        };
        Job job = job("isolated", "{}");
        Config cfg = new Config();
        cfg.lease = Duration.ofSeconds(2);
        cfg.heartbeat = Duration.ofMillis(250);
        cfg.minDuration = cfg.maxDuration = Duration.ofSeconds(10);
        start(cfg, brokenHeartbeat, new FakeExiter());

        long ms = waitUntil(
                () -> count("SELECT count(*) FROM executions WHERE job_id = ? AND outcome = 'fenced'", job.id()) == 1,
                Duration.ofSeconds(5));

        // limit = lease - heartbeat = 1.75 s; it must stop before the lease (2 s) can expire in the database
        assertThat(ms).isBetween(1500L, 2400L);
        assertThat(count("SELECT count(*) FROM effects")).isZero();
        assertThat(status(job.id())).isEqualTo(JobStatus.RUNNING); // nothing written; the reaper will take it back
    }

    // --------------------------------------------------------------------- 4.4

    @Test
    void threeFailedAttemptsEndInFailed() throws Exception {
        Job job = job("bad", "{}");
        Config cfg = new Config();
        cfg.failure = 1.0;
        start(cfg, REPO, new FakeExiter());
        waitUntil(() -> status(job.id()) == JobStatus.FAILED, Duration.ofSeconds(10));

        Job after = REPO.findById(job.id()).orElseThrow();
        assertThat(after.attempts()).isEqualTo(3);
        assertThat(after.lastError()).isEqualTo("emulated training failure");
        assertThat(count("SELECT count(*) FROM executions WHERE job_id = ? AND outcome = 'failed'", job.id())).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM effects")).isZero();
    }

    // --------------------------------------------------------------------- 4.5

    @Test
    void frozenWorkersJobIsReapedAndRunsAgainExactlyOnce() throws Exception {
        Job job = job("frozen", "{}");
        long claimedAt = System.nanoTime();
        ClaimedJob frozen = REPO.claim("frozen-worker", Duration.ofSeconds(5)).orElseThrow(); // never heartbeats
        REPO.insertExecution(frozen.id(), frozen.leaseToken(), "frozen-worker");

        Config cfg = new Config();
        startReaper(cfg, REPO);
        start(cfg, REPO, new FakeExiter());

        waitUntil(() -> count("""
                SELECT count(*) FROM job_transitions
                 WHERE job_id = ? AND old_status = 'running' AND new_status = 'queued'
                """, job.id()) == 1, Duration.ofSeconds(10));
        long requeuedAfterMs = (System.nanoTime() - claimedAt) / 1_000_000;
        waitUntil(() -> status(job.id()) == JobStatus.SUCCEEDED, Duration.ofSeconds(5));

        assertThat(requeuedAfterMs).isBetween(4000L, 7500L);
        assertThat(count("SELECT count(*) FROM executions WHERE job_id = ?", job.id())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM executions WHERE job_id = ? AND outcome IS NULL", job.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM effects WHERE job_id = ?", job.id())).isEqualTo(1);
        assertThat(REPO.findById(job.id()).orElseThrow().leaseToken()).isGreaterThan(frozen.leaseToken() + 1);
    }

    @Test
    void reaperKeepsRunningAfterATickThrows() throws Exception {
        AtomicBoolean thrown = new AtomicBoolean();
        JobRepository flaky = new JobRepository(JDBC, TX) {
            @Override
            public int reap(Duration maxRuntime, int batch) {
                if (thrown.compareAndSet(false, true)) {
                    throw new org.springframework.dao.DataAccessResourceFailureException("simulated hiccup");
                }
                return super.reap(maxRuntime, batch);
            }
        };
        Job job = job("expired", "{}");
        REPO.claim("dead", Duration.ofMillis(50));
        Config cfg = new Config();
        cfg.reaper = Duration.ofMillis(100);
        startReaper(cfg, flaky);

        waitUntil(() -> status(job.id()) == JobStatus.QUEUED, Duration.ofSeconds(3));
        assertThat(thrown).isTrue();
    }

    // --------------------------------------------------------------------- 4.6

    @Test
    void crashingJobEndsFailedAfterThreeClaims() throws Exception {
        Job job = job("poison", "{\"simulate\":\"crash\"}");
        Config cfg = new Config();
        cfg.hooks = true;
        cfg.lease = Duration.ofMillis(300);
        cfg.heartbeat = Duration.ofMillis(100);
        Reaper reaper = new Reaper(REPO, cfg.build());

        for (int crash = 1; crash <= 3; crash++) {
            FakeExiter exiter = new FakeExiter();
            start(cfg, REPO, exiter); // a fresh process start, like Docker restarting the container
            waitUntil(() -> exiter.halts.get() == 1 && exiter.exits.get() == 1, Duration.ofSeconds(5));
            Thread.sleep(400); // the dead worker's lease expires
            reaper.tick();
        }

        Job after = REPO.findById(job.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(JobStatus.FAILED);
        assertThat(after.attempts()).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM executions WHERE job_id = ? AND outcome IS NULL", job.id())).isEqualTo(3);
    }

    @Test
    void hungExecutionIsReapedAndTheWorkerExits() throws Exception {
        Job job = job("hang", "{\"simulate\":\"hang\"}");
        Config cfg = new Config();
        cfg.hooks = true;
        cfg.maxRuntime = Duration.ofSeconds(1);
        cfg.heartbeat = Duration.ofMillis(200);
        cfg.stopDeadline = Duration.ofMillis(500);
        FakeExiter exiter = new FakeExiter();
        start(cfg, REPO, exiter);
        waitUntil(() -> count("SELECT count(*) FROM executions WHERE job_id = ?", job.id()) == 1, Duration.ofSeconds(5));
        long claimToken = JDBC.queryForObject("SELECT lease_token FROM executions WHERE job_id = ?", Long.class, job.id());

        long exitedAfterMs = waitUntil(() -> exiter.exits.get() == 1, Duration.ofSeconds(5));
        assertThat(new Reaper(REPO, cfg.build()).tick()).isEqualTo(1);

        assertThat(exitedAfterMs).isLessThan(3000);
        Job after = REPO.findById(job.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(after.leaseToken()).isEqualTo(claimToken + 1);
    }

    @Test
    void testHooksAreIgnoredWhenDisabled() throws Exception {
        Job job = job("not-a-crash", "{\"simulate\":\"crash\"}");
        FakeExiter exiter = new FakeExiter();
        start(new Config(), REPO, exiter);
        waitUntil(() -> status(job.id()) == JobStatus.SUCCEEDED, Duration.ofSeconds(5));
        assertThat(exiter.halts.get()).isZero();
    }
}
