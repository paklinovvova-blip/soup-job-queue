package dev.soup.queue.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task 5.2. Each mutant must produce its bug in isolation, and the same scenario must be safe with
 * the mutant off. If a mutant could not break anything, a chaos run in that mode would prove nothing.
 */
class MutantTest {

    private static final Duration LEASE = Duration.ofSeconds(5);
    private static final Duration MAX_RUNTIME = Duration.ofSeconds(60);

    private record Db(JdbcTemplate jdbc, JobRepository repo) {
        int count(String sql, Object... args) {
            Integer n = jdbc.queryForObject(sql, Integer.class, args);
            return n == null ? 0 : n;
        }
    }

    private final List<HikariDataSource> pools = new ArrayList<>();

    @AfterEach
    void closePools() {
        pools.forEach(HikariDataSource::close);
    }

    private Db db(String name, Mutant mutant, String... locations) {
        HikariDataSource ds = TestDb.freshDatabase(name, locations);
        pools.add(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        return new Db(jdbc, new JobRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), mutant));
    }

    // ------------------------------------------------------------ naive-claim

    /** 16 threads race for 500 jobs. Returns the number of running → running audit rows (double claims). */
    private static int doubleClaims(Db db) throws Exception {
        db.jdbc.update("""
                INSERT INTO jobs (id, idempotency_key, request_hash, payload, status)
                SELECT gen_random_uuid(), 'k' || g, 'h', '{}', 'queued' FROM generate_series(1, 500) g
                """);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < 16; w++) {
            String worker = "w" + w;
            futures.add(pool.submit(() -> {
                go.await();
                while (db.repo.claim(worker, LEASE).isPresent()) {
                    // keep claiming until the queue is empty
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        return db.count("SELECT count(*) FROM job_transitions WHERE old_status = 'running' AND new_status = 'running'");
    }

    @Test
    void naiveClaimDoubleClaimsAndOnlyTheAuditLogShowsIt() throws Exception {
        Db correct = db("mut_claim_off", Mutant.NONE, "classpath:db/migration");
        Db naive = db("mut_claim_on", Mutant.NAIVE_CLAIM, "classpath:db/migration");

        assertThat(doubleClaims(correct)).isZero();
        int doubles = doubleClaims(naive);
        System.out.println("naive-claim: double claims among 500 jobs and 16 threads = " + doubles);
        assertThat(doubles).isPositive();
        // every double claim also burned an extra attempt: visible, but only indirectly
        assertThat(naive.count("SELECT count(*) FROM jobs WHERE attempts > 1")).isPositive();
    }

    // ------------------------------------------------------------- no-fencing

    /** A zombie whose lease was reaped and whose job was finished by a new owner tries to complete. */
    private static void zombieCompletesAfterReclaim(Db db) throws Exception {
        db.repo.insertIfAbsent(java.util.UUID.randomUUID(), "z", "h", "{}");
        ClaimedJob zombie = db.repo.claim("zombie", Duration.ofMillis(50)).orElseThrow();
        long zombieExec = db.repo.insertExecution(zombie.id(), zombie.leaseToken(), "zombie");
        Thread.sleep(150);
        db.repo.reap(MAX_RUNTIME, 100);
        ClaimedJob owner = db.repo.claim("owner", LEASE).orElseThrow();
        long ownerExec = db.repo.insertExecution(owner.id(), owner.leaseToken(), "owner");
        db.repo.complete(owner.id(), owner.leaseToken(), "owner", ownerExec);
        db.repo.complete(zombie.id(), zombie.leaseToken(), "zombie", zombieExec);
    }

    @Test
    void noFencingLetsAZombieWriteASecondEffect() throws Exception {
        Db correct = db("mut_fence_off", Mutant.NONE, "classpath:db/migration");
        Db broken = db("mut_fence_on", Mutant.NO_FENCING, "classpath:db/migration", "classpath:db/mutant/no-fencing");

        zombieCompletesAfterReclaim(correct);
        assertThat(correct.count("SELECT count(*) FROM effects")).isEqualTo(1);

        zombieCompletesAfterReclaim(broken);
        assertThat(broken.count("SELECT count(*) FROM effects")).isEqualTo(2); // I2 violated
        assertThat(broken.count("SELECT count(*) FROM pg_constraint WHERE conrelid = 'effects'::regclass AND contype = 'p'"))
                .isZero();
    }

    @Test
    void noFencingLetsACancelledJobComeBack() {
        Db broken = db("mut_cancel_on", Mutant.NO_FENCING, "classpath:db/migration", "classpath:db/mutant/no-fencing");
        Job job = broken.repo.insertIfAbsent(java.util.UUID.randomUUID(), "c", "h", "{}").orElseThrow();
        ClaimedJob c = broken.repo.claim("w", LEASE).orElseThrow();
        long exec = broken.repo.insertExecution(c.id(), c.leaseToken(), "w");
        broken.repo.cancel(job.id());

        assertThat(broken.repo.complete(c.id(), c.leaseToken(), "w", exec)).isTrue(); // with fencing: false
        assertThat(broken.repo.currentStatus(job.id())).contains(JobStatus.SUCCEEDED); // I5 violated
        assertThat(broken.count("SELECT count(*) FROM job_transitions WHERE old_status = 'cancelled'")).isEqualTo(1); // I9
    }

    // ----------------------------------------------------- worker-clock-lease

    @Test
    void workerClockLeaseWritesALeaseAnHourAhead() {
        Db correct = db("mut_clock_off", Mutant.NONE, "classpath:db/migration");
        Db skewed = db("mut_clock_on", Mutant.WORKER_CLOCK_LEASE, "classpath:db/migration");
        for (Db db : List.of(correct, skewed)) {
            db.repo.insertIfAbsent(java.util.UUID.randomUUID(), "l", "h", "{}");
            db.repo.claim("w", LEASE).orElseThrow();
        }
        // I10: no running job may have a lease further away than now() + 2 * lease
        String horizon = "SELECT count(*) FROM jobs WHERE status = 'running' AND lease_expires_at > now() + interval '10 seconds'";
        assertThat(correct.count(horizon)).isZero();
        assertThat(skewed.count(horizon)).isEqualTo(1);
        OffsetDateTime expiry = skewed.jdbc.queryForObject("SELECT lease_expires_at FROM jobs", OffsetDateTime.class);
        OffsetDateTime dbNow = skewed.jdbc.queryForObject("SELECT now()", OffsetDateTime.class);
        assertThat(Duration.between(dbNow, expiry)).isGreaterThan(Duration.ofMinutes(59));
    }
}
