package dev.soup.queue.job;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DELIBERATELY BROKEN claim, used only with {@code QUEUE_MUTANT=naive-claim}.
 * Two statements instead of one, no row lock, no status re-check: two workers that read the same id
 * both "win". The second UPDATE turns running into running with a new token, so fencing hides the
 * double effect, and only the audit log (I9: running → running) shows the double claim.
 */
final class NaiveClaimMutant {

    private NaiveClaimMutant() {
    }

    static Optional<ClaimedJob> claim(JdbcTemplate jdbc, String workerId, Duration lease) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM jobs WHERE status = 'queued' AND run_after <= now()
                 ORDER BY run_after, created_at LIMIT 1
                """, UUID.class);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.query("""
                UPDATE jobs
                   SET status = 'running', lease_token = lease_token + 1, lease_owner = ?,
                       lease_expires_at = now() + make_interval(secs => ?), attempts = attempts + 1,
                       claimed_at = now(), started_at = COALESCE(started_at, now()), updated_at = now()
                 WHERE id = ?
                RETURNING id, lease_token, attempts, payload::text AS payload
                """,
                (rs, n) -> new ClaimedJob(rs.getObject("id", UUID.class), rs.getLong("lease_token"),
                        rs.getInt("attempts"), rs.getString("payload")),
                workerId, JobRepository.seconds(lease), ids.get(0)).stream().findFirst();
    }
}
