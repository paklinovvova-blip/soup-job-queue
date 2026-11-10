package dev.soup.queue.job;

import static dev.soup.queue.job.TestDb.JDBC;
import static dev.soup.queue.job.TestDb.REPO;
import static dev.soup.queue.job.TestDb.count;
import static dev.soup.queue.job.TestDb.newJob;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tasks 2.1 and 2.2: schema, audit trigger and every repository statement on a real Postgres. */
class JobRepositoryTest {

    private static final Duration LEASE = Duration.ofSeconds(5);
    private static final Duration MAX_RUNTIME = Duration.ofSeconds(60);

    @BeforeEach
    void clean() {
        TestDb.clean();
    }

    @Test
    void effectPrimaryKeyExists() {
        assertThat(count("""
                SELECT count(*) FROM pg_constraint
                 WHERE conrelid = 'effects'::regclass AND contype = 'p'
                """)).isEqualTo(1);
    }

    @Test
    void auditLogsTransitionsButNotHeartbeats() {
        Job job = newJob("a");
        ClaimedJob claimed = REPO.claim("w1", LEASE).orElseThrow();
        assertThat(count("SELECT count(*) FROM job_transitions WHERE job_id = ?", job.id())).isEqualTo(1);

        assertThat(REPO.heartbeat(job.id(), claimed.leaseToken(), LEASE, MAX_RUNTIME)).isTrue();
        assertThat(count("SELECT count(*) FROM job_transitions WHERE job_id = ?", job.id())).isEqualTo(1);

        String edge = JDBC.queryForObject(
                "SELECT old_status || '>' || new_status || ':' || (new_token - old_token) FROM job_transitions WHERE job_id = ?",
                String.class, job.id());
        assertThat(edge).isEqualTo("queued>running:1");
    }

    @Test
    void insertIsIdempotentByKey() {
        UUID first = UUID.randomUUID();
        assertThat(REPO.insertIfAbsent(first, "key-1", "h", "{}")).isPresent();
        assertThat(REPO.insertIfAbsent(UUID.randomUUID(), "key-1", "h", "{}")).isEmpty();
        assertThat(REPO.findByKey("key-1").orElseThrow().id()).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM jobs")).isEqualTo(1);
    }

    @Test
    void freshJobIsQueuedWithNoAttempts() {
        Job job = newJob("fresh");
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.attempts()).isZero();
        assertThat(job.startedAt()).isNull();
        assertThat(job.finishedAt()).isNull();
        assertThat(REPO.findById(job.id())).contains(job);
    }

    @Test
    void claimSetsRunningLeaseAndBumpsToken() {
        Job job = newJob("c");
        ClaimedJob claimed = REPO.claim("w1", LEASE).orElseThrow();
        Job after = REPO.findById(job.id()).orElseThrow();

        assertThat(claimed.id()).isEqualTo(job.id());
        assertThat(claimed.leaseToken()).isEqualTo(job.leaseToken() + 1);
        assertThat(after.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(after.attempts()).isEqualTo(1);
        assertThat(after.leaseOwner()).isEqualTo("w1");
        assertThat(after.leaseExpiresAt()).isAfter(after.claimedAt().plusSeconds(4));
        assertThat(REPO.claim("w2", LEASE)).isEmpty();
    }

    @Test
    void claimTakesOldestFirst() {
        Job first = newJob("old");
        newJob("new");
        assertThat(REPO.claim("w1", LEASE).orElseThrow().id()).isEqualTo(first.id());
    }

    @Test
    void completeWritesEffectAndOutcome() {
        Job job = newJob("ok");
        ClaimedJob c = REPO.claim("w1", LEASE).orElseThrow();
        long exec = REPO.insertExecution(c.id(), c.leaseToken(), "w1");

        assertThat(REPO.complete(c.id(), c.leaseToken(), "w1", exec)).isTrue();

        Job after = REPO.findById(job.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(after.leaseToken()).isEqualTo(c.leaseToken());
        assertThat(after.finishedAt()).isNotNull();
        assertThat(count("SELECT count(*) FROM effects WHERE job_id = ? AND lease_token = ?", job.id(), c.leaseToken()))
                .isEqualTo(1);
        assertThat(JDBC.queryForObject("SELECT outcome FROM executions WHERE id = ?", String.class, exec))
                .isEqualTo("succeeded");
    }

    @Test
    void failRetriesWithBackoffThenFails() {
        Job job = newJob("f");
        for (int attempt = 1; attempt <= 3; attempt++) {
            JDBC.update("UPDATE jobs SET run_after = now() WHERE id = ?", job.id()); // skip the backoff wait
            ClaimedJob c = REPO.claim("w1", LEASE).orElseThrow();
            long exec = REPO.insertExecution(c.id(), c.leaseToken(), "w1");
            assertThat(REPO.fail(c.id(), c.leaseToken(), exec, "boom " + attempt, Duration.ofMillis(500))).isTrue();

            Job after = REPO.findById(job.id()).orElseThrow();
            assertThat(after.leaseToken()).isEqualTo(c.leaseToken() + 1);
            assertThat(after.lastError()).isEqualTo("boom " + attempt);
            if (attempt < 3) {
                assertThat(after.status()).isEqualTo(JobStatus.QUEUED);
                assertThat(after.runAfter()).isAfter(OffsetDateTime.now().plusNanos(200_000_000));
            } else {
                assertThat(after.status()).isEqualTo(JobStatus.FAILED);
                assertThat(after.finishedAt()).isNotNull();
            }
        }
        assertThat(count("SELECT count(*) FROM effects")).isZero();
        assertThat(count("SELECT count(*) FROM executions WHERE outcome = 'failed'")).isEqualTo(3);
    }

    @Test
    void retryIsNotClaimedBeforeRunAfter() {
        newJob("backoff");
        ClaimedJob c = REPO.claim("w1", LEASE).orElseThrow();
        long exec = REPO.insertExecution(c.id(), c.leaseToken(), "w1");
        REPO.fail(c.id(), c.leaseToken(), exec, "boom", Duration.ofSeconds(2));
        assertThat(REPO.claim("w2", LEASE)).isEmpty();
    }

    @Test
    void reapRequeuesExpiredLeaseWithNewToken() throws Exception {
        Job job = newJob("r");
        ClaimedJob c = REPO.claim("w1", Duration.ofMillis(100)).orElseThrow();
        Thread.sleep(300);

        assertThat(REPO.reap(MAX_RUNTIME, 100)).isEqualTo(1);

        Job after = REPO.findById(job.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(after.leaseToken()).isEqualTo(c.leaseToken() + 1);
        assertThat(after.leaseOwner()).isNull();
        assertThat(REPO.reap(MAX_RUNTIME, 100)).isZero();
    }

    @Test
    void reapFailsJobWithoutAttemptsLeft() throws Exception {
        Job job = newJob("poison");
        for (int i = 0; i < 3; i++) {
            REPO.claim("w" + i, Duration.ofMillis(50)).orElseThrow();
            Thread.sleep(150);
            assertThat(REPO.reap(MAX_RUNTIME, 100)).isEqualTo(1);
        }
        Job after = REPO.findById(job.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(JobStatus.FAILED);
        assertThat(after.attempts()).isEqualTo(3);
    }

    @Test
    void reapEnforcesMaxRuntimeEvenWithLiveHeartbeat() throws Exception {
        Job job = newJob("hung");
        ClaimedJob c = REPO.claim("w1", LEASE).orElseThrow();
        Thread.sleep(400);
        // the heartbeat refuses to extend past max runtime ...
        assertThat(REPO.heartbeat(c.id(), c.leaseToken(), LEASE, Duration.ofMillis(200))).isFalse();
        // ... and the reaper takes the job back although the lease itself has not expired
        assertThat(REPO.reap(Duration.ofMillis(200), 100)).isEqualTo(1);
        assertThat(REPO.findById(job.id()).orElseThrow().status()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void cancelQueuedAndRunningButNotFinished() {
        Job queued = newJob("q");
        Job running = newJob("r");
        Job done = newJob("d");
        REPO.claim("w", LEASE); // takes "q", the oldest
        JDBC.update("UPDATE jobs SET status = 'running', lease_token = lease_token + 1 WHERE id = ?", running.id());
        JDBC.update("UPDATE jobs SET status = 'succeeded' WHERE id = ?", done.id());

        assertThat(REPO.cancel(queued.id()).orElseThrow().status()).isEqualTo(JobStatus.CANCELLED);
        Job cancelledRunning = REPO.cancel(running.id()).orElseThrow();
        assertThat(cancelledRunning.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelledRunning.leaseOwner()).isNull();
        assertThat(REPO.cancel(done.id())).isEmpty();
        assertThat(REPO.cancel(queued.id())).isEmpty(); // already cancelled: no change
    }

    @Test
    void listWalksAllPagesNewestFirstWithStatusFilter() {
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            created.add(newJob("p" + i).id());
        }
        Set<UUID> seen = new HashSet<>();
        List<UUID> order = new ArrayList<>();
        JobPage.Cursor cursor = null;
        int pages = 0;
        do {
            JobPage page = REPO.list(null, cursor, 10);
            page.items().forEach(j -> {
                assertThat(seen.add(j.id())).isTrue();
                order.add(j.id());
            });
            cursor = page.next();
            pages++;
            if (pages == 1) {
                newJob("inserted-while-paging"); // must not shift later pages
            }
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsAll(created);
        assertThat(order.get(0)).isEqualTo(created.get(24));

        JDBC.update("UPDATE jobs SET status = 'failed' WHERE id = ?", created.get(3));
        JobPage failed = REPO.list(JobStatus.FAILED, null, 10);
        assertThat(failed.items()).extracting(Job::id).containsExactly(created.get(3));
        assertThat(failed.next()).isNull();
    }
}
