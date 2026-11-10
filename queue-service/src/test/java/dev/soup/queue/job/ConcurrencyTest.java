package dev.soup.queue.job;

import static dev.soup.queue.job.TestDb.DATA_SOURCE;
import static dev.soup.queue.job.TestDb.JDBC;
import static dev.soup.queue.job.TestDb.REPO;
import static dev.soup.queue.job.TestDb.count;
import static dev.soup.queue.job.TestDb.newJob;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** Tasks 2.3–2.6: the races the design claims to survive, on a real Postgres. */
class ConcurrencyTest {

    private static final Duration LEASE = Duration.ofSeconds(5);
    private static final Duration MAX_RUNTIME = Duration.ofSeconds(60);

    @BeforeEach
    void clean() {
        TestDb.clean();
    }

    // 2.3 --------------------------------------------------------------------

    @RepeatedTest(20)
    void sixteenWorkersClaimEveryJobExactlyOnce() throws Exception {
        JDBC.update("""
                INSERT INTO jobs (id, idempotency_key, request_hash, payload, status)
                SELECT gen_random_uuid(), 'k' || g, 'h', '{}', 'queued' FROM generate_series(1, 1000) g
                """);
        Set<UUID> claimed = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < 16; w++) {
            String worker = "w" + w;
            futures.add(pool.submit(() -> {
                start.await();
                while (true) {
                    var job = REPO.claim(worker, LEASE);
                    if (job.isEmpty()) {
                        return null;
                    }
                    if (!claimed.add(job.get().id())) {
                        duplicates.incrementAndGet();
                    }
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(duplicates.get()).isZero();
        assertThat(claimed).hasSize(1000);
        assertThat(count("SELECT count(*) FROM jobs WHERE attempts <> 1")).isZero();
        assertThat(count("SELECT count(*) FROM job_transitions WHERE old_status = 'running' AND new_status = 'running'"))
                .isZero();
    }

    @Test
    void claimSkipsRowLockedByAnotherTransaction() throws Exception {
        Job oldest = newJob("locked");
        Job next = newJob("free");
        try (Connection c = DATA_SOURCE.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM jobs WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, oldest.id());
                ps.executeQuery();
            }
            ClaimedJob got = assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> REPO.claim("w1", LEASE).orElseThrow());
            assertThat(got.id()).isEqualTo(next.id());
            c.rollback();
        }
    }

    // 2.4 --------------------------------------------------------------------

    @Test
    void staleTokenCannotHeartbeatCompleteOrFail() {
        Job job = newJob("z");
        ClaimedJob zombie = REPO.claim("zombie", Duration.ofMillis(1)).orElseThrow();
        long zombieExec = REPO.insertExecution(zombie.id(), zombie.leaseToken(), "zombie");
        REPO.cancel(job.id()); // any ownership change; here: cancel

        assertThat(REPO.heartbeat(job.id(), zombie.leaseToken(), LEASE, MAX_RUNTIME)).isFalse();
        assertThat(REPO.complete(job.id(), zombie.leaseToken(), "zombie", zombieExec)).isFalse();
        assertThat(REPO.fail(job.id(), zombie.leaseToken(), zombieExec, "x", Duration.ofMillis(1))).isFalse();

        assertThat(count("SELECT count(*) FROM effects")).isZero();
        assertThat(REPO.findById(job.id()).orElseThrow().status()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void zombieAfterReclaimIsFencedAndNewOwnerWins() throws Exception {
        Job job = newJob("zz");
        ClaimedJob zombie = REPO.claim("zombie", Duration.ofMillis(50)).orElseThrow();
        long zombieExec = REPO.insertExecution(zombie.id(), zombie.leaseToken(), "zombie");
        Thread.sleep(150);
        REPO.reap(MAX_RUNTIME, 100);
        ClaimedJob owner = REPO.claim("owner", LEASE).orElseThrow();
        long ownerExec = REPO.insertExecution(owner.id(), owner.leaseToken(), "owner");

        assertThat(REPO.complete(job.id(), zombie.leaseToken(), "zombie", zombieExec)).isFalse();
        assertThat(REPO.complete(job.id(), owner.leaseToken(), "owner", ownerExec)).isTrue();

        assertThat(JDBC.queryForObject("SELECT worker_id FROM effects WHERE job_id = ?", String.class, job.id()))
                .isEqualTo("owner");
        assertThat(count("SELECT count(*) FROM executions WHERE job_id = ?", job.id())).isEqualTo(2);
    }

    @Test
    void databaseRejectsSecondEffect() {
        Job job = newJob("dup");
        JDBC.update("INSERT INTO effects (job_id, lease_token, worker_id) VALUES (?, 1, 'a')", job.id());
        assertThatThrownBy(() ->
                JDBC.update("INSERT INTO effects (job_id, lease_token, worker_id) VALUES (?, 2, 'b')", job.id()))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void tokenBumpsOnEveryTransitionExceptSuccess() throws Exception {
        Job job = newJob("t");
        long t0 = job.leaseToken();
        ClaimedJob c1 = REPO.claim("w", LEASE).orElseThrow();                         // claim
        assertThat(c1.leaseToken()).isEqualTo(t0 + 1);
        long e1 = REPO.insertExecution(c1.id(), c1.leaseToken(), "w");
        REPO.fail(c1.id(), c1.leaseToken(), e1, "x", Duration.ofMillis(1));          // retry release
        assertThat(token(job.id())).isEqualTo(t0 + 2);
        Thread.sleep(20);
        ClaimedJob c2 = REPO.claim("w", Duration.ofMillis(50)).orElseThrow();        // claim
        assertThat(c2.leaseToken()).isEqualTo(t0 + 3);
        Thread.sleep(150);
        REPO.reap(MAX_RUNTIME, 100);                                                  // reap
        assertThat(token(job.id())).isEqualTo(t0 + 4);
        ClaimedJob c3 = REPO.claim("w", LEASE).orElseThrow();                         // claim
        long e3 = REPO.insertExecution(c3.id(), c3.leaseToken(), "w");
        REPO.complete(c3.id(), c3.leaseToken(), "w", e3);                            // success: no bump
        assertThat(token(job.id())).isEqualTo(c3.leaseToken()).isEqualTo(t0 + 5);

        Job other = newJob("t2");
        REPO.cancel(other.id());                                                      // cancel
        assertThat(token(other.id())).isEqualTo(other.leaseToken() + 1);
    }

    private static long token(UUID id) {
        return REPO.findById(id).orElseThrow().leaseToken();
    }

    // 2.5 --------------------------------------------------------------------

    @Test
    void cancelAndCompleteRaceHasExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int cancelWins = 0;
        int completeWins = 0;
        for (int i = 0; i < 500; i++) {
            Job job = newJob("race" + i);
            ClaimedJob c = REPO.claim("w", LEASE).orElseThrow();
            long exec = REPO.insertExecution(c.id(), c.leaseToken(), "w");
            CountDownLatch go = new CountDownLatch(1);
            Future<Boolean> cancel = pool.submit(() -> {
                go.await();
                return REPO.cancel(job.id()).isPresent();
            });
            Future<Boolean> complete = pool.submit(() -> {
                go.await();
                return REPO.complete(job.id(), c.leaseToken(), "w", exec);
            });
            go.countDown();
            boolean cancelled = cancel.get();
            boolean completed = complete.get();

            assertThat(cancelled ^ completed).as("exactly one winner, iteration %d", i).isTrue();
            JobStatus status = REPO.currentStatus(job.id()).orElseThrow();
            int effects = count("SELECT count(*) FROM effects WHERE job_id = ?", job.id());
            if (cancelled) {
                cancelWins++;
                assertThat(status).isEqualTo(JobStatus.CANCELLED);
                assertThat(effects).isZero();
            } else {
                completeWins++;
                assertThat(status).isEqualTo(JobStatus.SUCCEEDED);
                assertThat(effects).isEqualTo(1);
            }
        }
        pool.shutdown();
        System.out.printf("cancel/complete race: cancel won %d, complete won %d%n", cancelWins, completeWins);
        assertThat(TestDb.illegalTransitions()).isZero();
    }

    // 2.6 --------------------------------------------------------------------

    @Test
    void reaperSkipsLockedRowAndStalledTransactionIsAborted() throws Exception {
        Job stuck = newJob("stuck");
        Job other = newJob("other");
        REPO.claim("w1", Duration.ofMillis(50)).orElseThrow();
        REPO.claim("w2", Duration.ofMillis(50)).orElseThrow();
        Thread.sleep(150);

        Connection stalled = DATA_SOURCE.getConnection();
        stalled.setAutoCommit(false);
        try (PreparedStatement ps = stalled.prepareStatement("SELECT id FROM jobs WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, stuck.id());
            ps.executeQuery();
        }

        int reaped = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> REPO.reap(MAX_RUNTIME, 100));
        assertThat(reaped).isEqualTo(1);
        assertThat(REPO.currentStatus(other.id())).contains(JobStatus.QUEUED);
        assertThat(REPO.currentStatus(stuck.id())).contains(JobStatus.RUNNING);

        Thread.sleep(2_500); // idle_in_transaction_session_timeout = 2s
        assertThatThrownBy(() -> stalled.createStatement().execute("SELECT 1")).isInstanceOf(SQLException.class);
        stalled.close();

        assertThat(REPO.reap(MAX_RUNTIME, 100)).isEqualTo(1);
        assertThat(REPO.currentStatus(stuck.id())).contains(JobStatus.QUEUED);
    }
}
