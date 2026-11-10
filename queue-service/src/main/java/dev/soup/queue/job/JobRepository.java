package dev.soup.queue.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.soup.queue.QueueProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * All SQL that carries correctness lives here. Every state transition is one conditional UPDATE
 * (a compare-and-set on the row) or one short transaction. The rule for reading this class:
 * a write for a running job only succeeds if {@code status = 'running' AND lease_token = :token},
 * and every transition except running → succeeded bumps the token.
 * See openspec/changes/add-job-queue-service/design.md, D5–D11.
 * <p>
 * With a {@link Mutant} other than NONE, one protection is removed on purpose (design D15). The mutant
 * branches are kept next to the correct statements, so the difference is visible in one place.
 */
@Repository
public class JobRepository {

    // Leading space on purpose: text blocks strip trailing spaces, so "RETURNING """ + JOB_COLUMNS needs it.
    private static final String JOB_COLUMNS = " " + """
            id, idempotency_key, request_hash, payload::text AS payload, status, attempts, max_attempts,
            lease_token, lease_owner, lease_expires_at, claimed_at, run_after, last_error,
            created_at, updated_at, started_at, finished_at""";

    private static final Logger log = LoggerFactory.getLogger(JobRepository.class);
    static final Duration MUTANT_CLOCK_SKEW = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Mutant mutant;

    public JobRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this(jdbc, tx, Mutant.NONE);
    }

    @Autowired
    public JobRepository(JdbcTemplate jdbc, TransactionTemplate tx, QueueProperties props) {
        this(jdbc, tx, Mutant.parse(props.mutant()));
    }

    public JobRepository(JdbcTemplate jdbc, TransactionTemplate tx, Mutant mutant) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mutant = mutant;
        if (mutant != Mutant.NONE) {
            log.warn("!!!!!!!!!!!!!!!! MUTANT MODE {}: a protection is DISABLED ON PURPOSE. Never run this in production !!!!!!!!!!!!!!!!",
                    mutant.flag());
        }
    }

    public Mutant mutant() {
        return mutant;
    }

    // ---------------------------------------------------------------- API side

    /**
     * Idempotent insert. Returns the new job, or empty if the key already exists.
     * Two concurrent inserts of one key: the second waits on the unique index until the first
     * commits, then does nothing (design D9).
     */
    public Optional<Job> insertIfAbsent(UUID id, String key, String requestHash, String payloadJson) {
        List<Job> rows = jdbc.query("""
                INSERT INTO jobs (id, idempotency_key, request_hash, payload, status)
                VALUES (?, ?, ?, ?::jsonb, 'queued')
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING """ + JOB_COLUMNS,
                JOB_MAPPER, id, key, requestHash, payloadJson);
        return rows.stream().findFirst();
    }

    public Optional<Job> findByKey(String key) {
        return jdbc.query("SELECT " + JOB_COLUMNS + " FROM jobs WHERE idempotency_key = ?", JOB_MAPPER, key)
                .stream().findFirst();
    }

    public Optional<Job> findById(UUID id) {
        return jdbc.query("SELECT " + JOB_COLUMNS + " FROM jobs WHERE id = ?", JOB_MAPPER, id)
                .stream().findFirst();
    }

    /** Keyset pagination, newest first, ties broken by id (design D11). */
    public JobPage list(JobStatus status, JobPage.Cursor after, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(JOB_COLUMNS).append(" FROM jobs WHERE true");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.db());
        }
        if (after != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
            args.add(after.createdAt());
            args.add(after.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit + 1); // one extra row tells whether a next page exists
        List<Job> rows = jdbc.query(sql.toString(), JOB_MAPPER, args.toArray());
        if (rows.size() <= limit) {
            return new JobPage(rows, null);
        }
        List<Job> page = rows.subList(0, limit);
        Job last = page.get(limit - 1);
        return new JobPage(List.copyOf(page), new JobPage.Cursor(last.createdAt(), last.id()));
    }

    /**
     * Cancel a queued or running job. Bumps the token, so a running worker loses its lease:
     * its next heartbeat or commit updates 0 rows (design D8). Empty = nothing was changed.
     */
    public Optional<Job> cancel(UUID id) {
        return jdbc.query("""
                UPDATE jobs
                   SET status = 'cancelled', lease_token = lease_token + 1,
                       lease_owner = NULL, lease_expires_at = NULL, finished_at = now(), updated_at = now()
                 WHERE id = ? AND status IN ('queued', 'running')
                RETURNING """ + JOB_COLUMNS,
                JOB_MAPPER, id).stream().findFirst();
    }

    // ------------------------------------------------------------- worker side

    /**
     * Claim the oldest eligible job. The subquery locks one row and skips rows locked by others,
     * so two workers never get the same job and never wait for each other (design D5).
     * Keep {@code id = (scalar subquery)}: with {@code IN} the planner may lock more rows.
     */
    public Optional<ClaimedJob> claim(String workerId, Duration lease) {
        if (mutant == Mutant.NAIVE_CLAIM) {
            return NaiveClaimMutant.claim(jdbc, workerId, lease);
        }
        if (mutant == Mutant.WORKER_CLOCK_LEASE) {
            return jdbc.query("""
                    UPDATE jobs
                       SET status = 'running', lease_token = lease_token + 1, lease_owner = ?,
                           lease_expires_at = ?, attempts = attempts + 1,
                           claimed_at = now(), started_at = COALESCE(started_at, now()), updated_at = now()
                     WHERE id = (SELECT id FROM jobs
                                  WHERE status = 'queued' AND run_after <= now()
                                  ORDER BY run_after, created_at
                                  FOR UPDATE SKIP LOCKED
                                  LIMIT 1)
                    RETURNING id, lease_token, attempts, payload::text AS payload
                    """, CLAIM_MAPPER, workerId, WorkerClockLeaseMutant.leaseExpiry(MUTANT_CLOCK_SKEW, lease))
                    .stream().findFirst();
        }
        return jdbc.query("""
                UPDATE jobs
                   SET status = 'running', lease_token = lease_token + 1, lease_owner = ?,
                       lease_expires_at = now() + make_interval(secs => ?), attempts = attempts + 1,
                       claimed_at = now(), started_at = COALESCE(started_at, now()), updated_at = now()
                 WHERE id = (SELECT id FROM jobs
                              WHERE status = 'queued' AND run_after <= now()
                              ORDER BY run_after, created_at
                              FOR UPDATE SKIP LOCKED
                              LIMIT 1)
                RETURNING id, lease_token, attempts, payload::text AS payload
                """, CLAIM_MAPPER, workerId, seconds(lease)).stream().findFirst();
    }

    private static final RowMapper<ClaimedJob> CLAIM_MAPPER = (rs, n) -> new ClaimedJob(
            rs.getObject("id", UUID.class), rs.getLong("lease_token"), rs.getInt("attempts"), rs.getString("payload"));

    /** Record that a real run starts. Separate from the effect, so runs can be counted. */
    public long insertExecution(UUID jobId, long token, String workerId) {
        Long id = jdbc.queryForObject("""
                INSERT INTO executions (job_id, lease_token, worker_id) VALUES (?, ?, ?) RETURNING id
                """, Long.class, jobId, token, workerId);
        return id;
    }

    /**
     * Extend the lease, but only for the current owner and only within the max runtime.
     * {@code false} means the lease was taken away (cancel, reap) and the worker must stop.
     */
    public boolean heartbeat(UUID jobId, long token, Duration lease, Duration maxRuntime) {
        if (mutant == Mutant.NO_FENCING) {
            return jdbc.update("UPDATE jobs SET lease_expires_at = now() + make_interval(secs => ?), updated_at = now() WHERE id = ?",
                    seconds(lease), jobId) == 1;
        }
        if (mutant == Mutant.WORKER_CLOCK_LEASE) {
            return jdbc.update("""
                    UPDATE jobs SET lease_expires_at = ?, updated_at = now()
                     WHERE id = ? AND status = 'running' AND lease_token = ?
                       AND claimed_at > now() - make_interval(secs => ?)
                    """, WorkerClockLeaseMutant.leaseExpiry(MUTANT_CLOCK_SKEW, lease), jobId, token,
                    seconds(maxRuntime)) == 1;
        }
        return jdbc.update("""
                UPDATE jobs SET lease_expires_at = now() + make_interval(secs => ?), updated_at = now()
                 WHERE id = ? AND status = 'running' AND lease_token = ?
                   AND claimed_at > now() - make_interval(secs => ?)
                """, seconds(lease), jobId, token, seconds(maxRuntime)) == 1;
    }

    /**
     * Fenced, atomic completion: status change, effect and execution outcome in one transaction.
     * The UPDATE on jobs runs first and takes the row lock, so a concurrent cancel or reap waits
     * for this commit and then re-checks its WHERE (design D7). {@code false} = fenced, nothing written.
     */
    public boolean complete(UUID jobId, long token, String workerId, long executionId) {
        Boolean done = tx.execute(status -> {
            int updated = mutant == Mutant.NO_FENCING
                    ? jdbc.update("""
                        UPDATE jobs SET status = 'succeeded', finished_at = now(),
                               lease_owner = NULL, lease_expires_at = NULL, updated_at = now()
                         WHERE id = ?
                        """, jobId)
                    : jdbc.update("""
                        UPDATE jobs SET status = 'succeeded', finished_at = now(),
                               lease_owner = NULL, lease_expires_at = NULL, updated_at = now()
                         WHERE id = ? AND status = 'running' AND lease_token = ?
                        """, jobId, token);
            if (updated == 0) {
                status.setRollbackOnly();
                return false;
            }
            jdbc.update("INSERT INTO effects (job_id, lease_token, worker_id) VALUES (?, ?, ?)",
                    jobId, token, workerId);
            jdbc.update("UPDATE executions SET outcome = 'succeeded', finished_at = now() WHERE id = ?",
                    executionId);
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    /**
     * Fenced failure. Retry with exponential backoff while attempts remain, otherwise 'failed'.
     * The backoff is computed from the database clock. {@code false} = fenced, nothing written.
     */
    public boolean fail(UUID jobId, long token, long executionId, String error, Duration retryBase) {
        Boolean done = tx.execute(status -> {
            int updated = jdbc.update("""
                    UPDATE jobs
                       SET status      = CASE WHEN attempts >= max_attempts THEN 'failed' ELSE 'queued' END,
                           finished_at = CASE WHEN attempts >= max_attempts THEN now() END,
                           run_after   = CASE WHEN attempts >= max_attempts THEN run_after
                                              ELSE now() + make_interval(secs => ? * power(2, attempts - 1)
                                                                                   * (1 + random() * 0.2)) END,
                           lease_token = lease_token + 1, lease_owner = NULL, lease_expires_at = NULL,
                           last_error  = ?, updated_at = now()
                     WHERE id = ?""" + (mutant == Mutant.NO_FENCING ? "" : " AND status = 'running' AND lease_token = ?"),
                    mutant == Mutant.NO_FENCING
                            ? new Object[] {seconds(retryBase), error, jobId}
                            : new Object[] {seconds(retryBase), error, jobId, token});
            if (updated == 0) {
                status.setRollbackOnly();
                return false;
            }
            jdbc.update("UPDATE executions SET outcome = 'failed', finished_at = now() WHERE id = ?", executionId);
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    /** Close an execution that did not commit: 'fenced' or 'cancelled'. Never overwrites an outcome. */
    public void recordOutcome(long executionId, String outcome) {
        jdbc.update("UPDATE executions SET outcome = ?, finished_at = now() WHERE id = ? AND outcome IS NULL",
                outcome, executionId);
    }

    public Optional<JobStatus> currentStatus(UUID jobId) {
        return jdbc.query("SELECT status FROM jobs WHERE id = ?",
                (rs, n) -> JobStatus.fromDb(rs.getString(1)), jobId).stream().findFirst();
    }

    /**
     * Return expired or overrunning jobs to the queue, or fail them when no attempts remain.
     * Batched and {@code SKIP LOCKED}: a row locked by a stalled transaction is skipped, not waited on,
     * and several reapers never deadlock on each other (design D6). Returns the number of jobs reaped.
     */
    public int reap(Duration maxRuntime, int batch) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT id FROM jobs
                     WHERE status = 'running'
                       AND (lease_expires_at < now() OR claimed_at < now() - make_interval(secs => ?))
                     ORDER BY id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?)
                UPDATE jobs j
                   SET status      = CASE WHEN j.attempts >= j.max_attempts THEN 'failed' ELSE 'queued' END,
                       finished_at = CASE WHEN j.attempts >= j.max_attempts THEN now() END,
                       lease_token = j.lease_token + 1, lease_owner = NULL, lease_expires_at = NULL,
                       run_after = now(), last_error = 'lease expired or max runtime exceeded', updated_at = now()
                  FROM expired e
                 WHERE j.id = e.id
                """, seconds(maxRuntime), batch);
    }

    // ----------------------------------------------------------------- helpers

    static double seconds(Duration d) {
        return d.toNanos() / 1_000_000_000.0;
    }

    static final RowMapper<Job> JOB_MAPPER = JobRepository::mapJob;

    private static Job mapJob(ResultSet rs, int rowNum) throws SQLException {
        return new Job(
                rs.getObject("id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getString("payload"),
                JobStatus.fromDb(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getLong("lease_token"),
                rs.getString("lease_owner"),
                rs.getObject("lease_expires_at", OffsetDateTime.class),
                rs.getObject("claimed_at", OffsetDateTime.class),
                rs.getObject("run_after", OffsetDateTime.class),
                rs.getString("last_error"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class));
    }
}
