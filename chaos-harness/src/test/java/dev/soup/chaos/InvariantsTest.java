package dev.soup.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Task 7.4: corrupt a database by hand in each way the invariants are meant to catch, and check
 * that exactly that invariant reports it. A checker that cannot see a planted bug proves nothing.
 * The schema is the service's own migrations, read from the queue-service module.
 */
class InvariantsTest {

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");
    private static final String MIGRATIONS = "filesystem:../queue-service/src/main/resources/db/migration";
    private static final String NO_FENCING = "filesystem:../queue-service/src/main/resources/db/mutant/no-fencing";

    @BeforeAll
    static void start() {
        PG.start();
    }

    @AfterAll
    static void stop() {
        PG.stop();
    }

    /** A fresh database with one healthy succeeded job "ok" and one cancelled job "c". */
    private Db freshDb(String name, String... locations) throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + name);
        }
        String url = PG.getJdbcUrl().replace("/" + PG.getDatabaseName(), "/" + name);
        Flyway.configure().dataSource(url, PG.getUsername(), PG.getPassword()).locations(locations).load().migrate();
        exec(url, """
                INSERT INTO jobs (id, idempotency_key, request_hash, payload, status) VALUES
                  ('00000000-0000-0000-0000-000000000001', 'ok', 'h', '{}', 'queued'),
                  ('00000000-0000-0000-0000-000000000002', 'c',  'h', '{}', 'queued');
                UPDATE jobs SET status = 'running', lease_token = 1, attempts = 1, lease_expires_at = now() + interval '5 s',
                                claimed_at = now() WHERE idempotency_key = 'ok';
                INSERT INTO executions (job_id, lease_token, worker_id, outcome)
                  VALUES ('00000000-0000-0000-0000-000000000001', 1, 'w', 'succeeded');
                UPDATE jobs SET status = 'succeeded', lease_expires_at = NULL WHERE idempotency_key = 'ok';
                INSERT INTO effects (job_id, lease_token, worker_id) VALUES ('00000000-0000-0000-0000-000000000001', 1, 'w');
                UPDATE jobs SET status = 'cancelled', lease_token = 1 WHERE idempotency_key = 'c';
                """);
        return new Db(url, PG.getUsername(), PG.getPassword());
    }

    private static void exec(String url, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static final String OK_ID = "00000000-0000-0000-0000-000000000001";
    private static final String C_ID = "00000000-0000-0000-0000-000000000002";

    private static List<ClientLog.Entry> healthyLog() {
        List<ClientLog.Entry> log = new ArrayList<>();
        log.add(new ClientLog.Entry(1, "create", "ok", OK_ID, 201, "queued", null, false));
        log.add(new ClientLog.Entry(2, "create", "ok", OK_ID, 200, "queued", null, true));
        log.add(new ClientLog.Entry(3, "create", "c", C_ID, 201, "queued", null, false));
        log.add(new ClientLog.Entry(4, "cancel", "c", C_ID, 200, "cancelled", null, false));
        return log;
    }

    private static Map<String, Invariants.Check> check(Db db, List<ClientLog.Entry> log) {
        return new Invariants(db, log, Set.of("ok", "c", "lost")).afterQuiescence().stream()
                .collect(Collectors.toMap(Invariants.Check::id, Function.identity()));
    }

    private static List<String> failing(Map<String, Invariants.Check> checks) {
        return checks.values().stream().filter(c -> !c.passed()).map(Invariants.Check::id).sorted().toList();
    }

    @Test
    void healthyDatabasePassesEverything() throws Exception {
        assertThat(failing(check(freshDb("inv_ok", MIGRATIONS), healthyLog()))).isEmpty();
    }

    @Test
    void duplicateEffectIsCaughtByI2AndI3() throws Exception {
        Db db = freshDb("inv_dup", MIGRATIONS, NO_FENCING);
        exec(db.url(), "INSERT INTO effects (job_id, lease_token, worker_id) VALUES ('" + OK_ID + "', 1, 'zombie')");
        Map<String, Invariants.Check> checks = check(db, healthyLog());
        assertThat(failing(checks)).containsExactly("I2", "I3");
        assertThat(checks.get("I2").sample()).containsExactly(OK_ID);
    }

    @Test
    void stuckJobIsCaughtByI4() throws Exception {
        Db db = freshDb("inv_stuck", MIGRATIONS);
        exec(db.url(), "INSERT INTO jobs (id, idempotency_key, request_hash, payload, status, lease_token, attempts) "
                + "VALUES ('" + UUID.randomUUID() + "', 'lost', 'h', '{}', 'running', 1, 1)");
        assertThat(failing(check(db, healthyLog()))).containsExactly("I4");
    }

    @Test
    void illegalTransitionIsCaughtByI9() throws Exception {
        Db db = freshDb("inv_edge", MIGRATIONS);
        exec(db.url(), "UPDATE jobs SET status = 'running' WHERE id = '" + C_ID + "'"); // out of a terminal state
        exec(db.url(), "UPDATE jobs SET status = 'cancelled', lease_token = lease_token + 1 WHERE id = '" + C_ID + "'");
        Map<String, Invariants.Check> checks = check(db, healthyLog());
        assertThat(checks.get("I9").passed()).isFalse();
        assertThat(checks.get("I9").sample()).containsExactly(C_ID);
    }

    @Test
    void lostAcceptedJobIsCaughtByI1() throws Exception {
        List<ClientLog.Entry> log = healthyLog();
        log.add(new ClientLog.Entry(5, "create", "lost", "00000000-0000-0000-0000-00000000dead", 201, "queued", null, false));
        Map<String, Invariants.Check> checks = check(freshDb("inv_lost", MIGRATIONS), log);
        assertThat(failing(checks)).contains("I1");
        assertThat(checks.get("I1").sample()).containsExactly("lost:lost");
    }

    @Test
    void cancelAnsweredCancelledButJobSucceededIsCaughtByI5() throws Exception {
        List<ClientLog.Entry> log = healthyLog();
        log.add(new ClientLog.Entry(6, "cancel", "ok", OK_ID, 200, "cancelled", null, false));
        Map<String, Invariants.Check> checks = check(freshDb("inv_cancel", MIGRATIONS), log);
        assertThat(failing(checks)).containsExactly("I5");
    }

    @Test
    void twoJobIdsForOneKeyAreCaughtByI6() throws Exception {
        List<ClientLog.Entry> log = healthyLog();
        log.add(new ClientLog.Entry(7, "resend", "ok", C_ID, 200, "queued", null, false));
        assertThat(failing(check(freshDb("inv_ids", MIGRATIONS), log))).containsExactly("I6");
    }

    @Test
    void effectWithAStaleTokenIsCaughtByI7() throws Exception {
        Db db = freshDb("inv_token", MIGRATIONS);
        exec(db.url(), "UPDATE effects SET lease_token = 0 WHERE job_id = '" + OK_ID + "'");
        assertThat(failing(check(db, healthyLog()))).containsExactly("I7");
    }

    @Test
    void failedJobWithAttemptsLeftIsCaughtByI8() throws Exception {
        Db db = freshDb("inv_attempts", MIGRATIONS);
        exec(db.url(), "INSERT INTO jobs (id, idempotency_key, request_hash, payload, status, lease_token, attempts) "
                + "VALUES ('" + UUID.randomUUID() + "', 'lost', 'h', '{}', 'failed', 1, 1)");
        assertThat(failing(check(db, healthyLog()))).containsExactly("I8");
    }

    @Test
    void farFutureLeaseIsCaughtByI10() throws Exception {
        Db db = freshDb("inv_lease", MIGRATIONS);
        String id = UUID.randomUUID().toString();
        exec(db.url(), "INSERT INTO jobs (id, idempotency_key, request_hash, payload, status, lease_token, attempts, lease_expires_at) "
                + "VALUES ('" + id + "', 'lost', 'h', '{}', 'running', 1, 1, now() + interval '1 hour')");
        assertThat(db.strings(Invariants.LeaseHorizon.query(Duration.ofSeconds(5)))).containsExactly(id);
        exec(db.url(), "UPDATE jobs SET lease_expires_at = now() + interval '5 seconds' WHERE id = '" + id + "'");
        assertThat(db.strings(Invariants.LeaseHorizon.query(Duration.ofSeconds(5)))).isEmpty();
    }
}
