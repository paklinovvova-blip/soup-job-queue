# Design

## Context

The repository is empty. Motivation and scope are in `proposal.md`, and observable behaviour is in
`specs/job-api`, `specs/job-execution` and `specs/chaos-verification`. A self-check review was run before implementation
(`REVIEW.md`). This version of the design applies its findings. Constraints that shape the design:

- One command, `docker compose up`, must start everything. At least 4 workers, 8 in the chaos run.
- Workers are killed with SIGKILL and paused, so they must be separate OS processes (containers), not threads.
- The author must be able to explain every line that carries correctness, which favours explicit SQL over an ORM.
- Time budget is 4–6 hours of work, so mechanisms must be simple and standard. `tasks.md` marks a core path and stretch items.

## Goals / Non-Goals

**Goals:**
- Correctness under worker crashes, zombies, duplicate requests and cancel races, proven by a harness that is itself shown to catch seeded bugs.
- Every state transition is a single conditional SQL statement, or one short transaction, that can be read and reasoned about in isolation.
- Honest evidence: raw logs of every run, the limits of the harness, and a verdict at 100 RPS against criteria fixed before the run.

**Non-Goals:**
- Postgres high availability, failover, backups. The API and Postgres are never killed by the harness.
- Authentication, authorization, multi-tenancy, rate limiting.
- Real training workloads, external side effects (S3, GPUs), job priorities, scheduling in the future beyond retry backoff.
- Push-based wakeups (`LISTEN/NOTIFY`), batch claiming, multi-threaded workers. These are listed as next steps.

## Decisions

### D1. Postgres 16 as the queue and the source of truth
Postgres gives row-level locks, `FOR UPDATE SKIP LOCKED`, triggers, and `now()` as a single shared clock.
- *Alternative: SQLite.* One writer for the whole database, no `SKIP LOCKED`, and a file shared across 8 containers is fragile. It fits only one process with worker threads, and then `kill -9` kills every worker at once.
- *Alternative: a broker (RabbitMQ, Kafka) plus a database.* Per-job state (status, cancel, list, idempotency) still needs a database, so this means two systems kept consistent through an outbox. Not justified at this scale.

### D2. Java 21, Spring Boot 3, `JdbcTemplate`, Flyway, Maven
- *Alternative: JPA/Hibernate.* It hides when statements are flushed and makes the read-then-write anti-pattern easy to write by accident. Correctness here depends on the exact `WHERE` clause and the affected row count, which `JdbcTemplate.update()` returns directly.

### D3. One jar, two roles, separate containers
`queue-service` starts as `api` or `worker` via `APP_ROLE`. Compose runs 1 `api` and `worker` with `deploy.replicas: 8`.
Flyway migrations run only in the `api` role. Workers wait until the schema version is present before they start polling.
The reaper (D6) runs in both roles, so expired jobs are still reaped while all workers are down.
- *Alternative: separate API and worker modules.* That means more build plumbing and duplicated SQL, with no benefit to correctness.

### D4. Data model

```sql
CREATE TABLE jobs (
  id               uuid PRIMARY KEY,
  idempotency_key  text        NOT NULL UNIQUE,
  request_hash     text        NOT NULL,             -- sha256 of canonical JSON payload (D9)
  payload          jsonb       NOT NULL,
  status           text        NOT NULL CHECK (status IN ('queued','running','succeeded','failed','cancelled')),
  attempts         int         NOT NULL DEFAULT 0,
  max_attempts     int         NOT NULL DEFAULT 3,
  lease_token      bigint      NOT NULL DEFAULT 0,   -- fencing token: +1 on every transition except running→succeeded
  lease_owner      text,
  lease_expires_at timestamptz,
  claimed_at       timestamptz,                      -- start of the current attempt (max runtime)
  run_after        timestamptz NOT NULL DEFAULT now(),
  last_error       text,                             -- error of the most recent failed attempt
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  started_at       timestamptz,                      -- first claim
  finished_at      timestamptz
);
CREATE INDEX jobs_queued_idx      ON jobs (run_after, created_at) WHERE status = 'queued';
CREATE INDEX jobs_running_idx     ON jobs (lease_expires_at)      WHERE status = 'running';
CREATE INDEX jobs_list_idx        ON jobs (created_at DESC, id DESC);
CREATE INDEX jobs_status_list_idx ON jobs (status, created_at DESC, id DESC);

CREATE TABLE executions (               -- one row per real run; counts runs, not effects
  id          bigserial PRIMARY KEY,
  job_id      uuid        NOT NULL REFERENCES jobs(id),
  lease_token bigint      NOT NULL,
  worker_id   text        NOT NULL,
  started_at  timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  outcome     text CHECK (outcome IN ('succeeded','failed','fenced','cancelled'))  -- NULL = worker died
);
CREATE INDEX executions_job_idx ON executions (job_id);

CREATE TABLE effects (                  -- the emulated side effect
  job_id      uuid PRIMARY KEY REFERENCES jobs(id),   -- at most one effect per job, enforced by the DB
  lease_token bigint      NOT NULL,
  worker_id   text        NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE job_transitions (          -- audit log for invariant I9, written by a trigger
  id         bigserial PRIMARY KEY,
  job_id     uuid        NOT NULL,
  old_status text        NOT NULL,
  new_status text        NOT NULL,
  old_token  bigint      NOT NULL,
  new_token  bigint      NOT NULL,
  at         timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- AFTER UPDATE ON jobs FOR EACH ROW: insert when status changed, or lease_token changed,
-- or OLD.status is terminal. Heartbeats change neither, so they are not logged.
```
The trigger lives in the database, so application code, including mutants, cannot bypass it.
It costs one insert per transition, about 3–4 per job, which is acceptable here. In production it could be sampled or dropped.

### D5. Claim: one statement with `FOR UPDATE SKIP LOCKED`
```sql
UPDATE jobs SET status='running', lease_token=lease_token+1, lease_owner=:worker,
       lease_expires_at=now()+:lease, attempts=attempts+1, claimed_at=now(),
       started_at=COALESCE(started_at, now()), updated_at=now()
 WHERE id = (SELECT id FROM jobs WHERE status='queued' AND run_after<=now()
             ORDER BY run_after, created_at FOR UPDATE SKIP LOCKED LIMIT 1)
RETURNING id, lease_token, payload;
```
Keep `id = (scalar subquery)`. With `IN (...)` the planner may run the subquery more than once and lock more rows than intended.
Then, in its own short transaction, the worker inserts the `executions` row. If that insert fails, the worker does not execute,
and the lease expires. If the worker dies between the two statements, the run is not recorded, but no work was done either.
Polling: claim again immediately after a job ends. Back off 100 ms → 1 s with jitter only while claims return nothing.
- *Alternative: `SELECT` then `UPDATE ... WHERE id=:id`.* This double-claims. It is the `naive-claim` mutant, and I9 catches it as `running → running`.
- *Alternative: advisory locks.* They are session-scoped and add a second locking concept to explain.

### D6. Lease, heartbeat, reaper, maximum runtime, worker watchdog
Defaults: lease 5 s, heartbeat 1 s, reaper 1 s, max runtime 60 s, stop deadline 5 s.
```sql
-- heartbeat
UPDATE jobs SET lease_expires_at=now()+:lease, updated_at=now()
 WHERE id=:id AND status='running' AND lease_token=:token AND claimed_at > now()-:maxRuntime;

-- reaper: batched, skips rows locked by others, never waits on a stalled transaction
WITH expired AS (
  SELECT id FROM jobs
   WHERE status='running' AND (lease_expires_at < now() OR claimed_at < now() - :maxRuntime)
   ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 100)
UPDATE jobs j
   SET status = CASE WHEN j.attempts>=j.max_attempts THEN 'failed' ELSE 'queued' END,
       finished_at = CASE WHEN j.attempts>=j.max_attempts THEN now() END,
       lease_token=j.lease_token+1, lease_owner=NULL, lease_expires_at=NULL, run_after=now(),
       last_error='lease expired or max runtime exceeded', updated_at=now()
  FROM expired e WHERE j.id = e.id;
```
- **One attempt object.** Each claim creates an immutable `Attempt(jobId, token, AtomicBoolean stop, long lastHeartbeatOkNanos)`.
  A heartbeat task is scheduled for that attempt and cancelled before the next claim. Work slices (≤50 ms) check `attempt.stop`.
  No `Thread.interrupt`, so a late signal can never leak into the next job.
- **Heartbeat outcomes.** 0 rows → set `stop`. An exception → log and retry on the next tick. If `System.nanoTime() - lastHeartbeatOkNanos > lease − heartbeat`,
  measured from the moment the last successful heartbeat was *sent*, set `stop`. That is self-fencing on the monotonic clock.
- **Watchdog.** If `stop` was set and the execution has not returned within the stop deadline, the worker logs an error and calls `System.exit(1)`.
  Docker's restart policy (`restart: unless-stopped`) brings the worker back. The harness also restarts containers it killed itself.
- **Periodic tasks.** Every scheduled body is wrapped in `try/catch (Throwable)` with error logging, so one exception never cancels the timer.
- **Stalled transactions.** Worker and API connections set `idle_in_transaction_session_timeout = 2s`, so a paused worker holding a row lock in an open
  transaction is aborted by Postgres, and its locks are released.
- *Alternative: workers register in a `workers` table and are declared dead when their row goes stale.* That is an extra table and an extra failure mode. Per-job leases are enough.

### D7. Fenced completion and failure
```sql
BEGIN;
UPDATE jobs SET status='succeeded', finished_at=now(), lease_owner=NULL, lease_expires_at=NULL, updated_at=now()
 WHERE id=:id AND status='running' AND lease_token=:token;          -- 0 rows → ROLLBACK, outcome 'fenced'
INSERT INTO effects(job_id, lease_token, worker_id) VALUES (:id, :token, :worker);
UPDATE executions SET outcome='succeeded', finished_at=now() WHERE id=:execId;
COMMIT;
```
Failure uses the same guard, sets `lease_token=lease_token+1`, and moves the job to `queued` with `run_after = now() + 500ms * 2^(attempts-1)` (+0–20% jitter) or to `failed`.
After a rollback the worker records the outcome (`fenced` or `cancelled`, read from the current job status) in a separate statement.
`UPDATE jobs` comes first, so it takes the row lock before anything else: a concurrent cancel or reap waits for the commit and then re-checks its `WHERE`.
Token rule: every transition bumps the token except `running → succeeded`, so the effect's token stays equal to the job's final token (I7).

### D8. Cancel: immediate, with lease revocation (author's choice)
```sql
UPDATE jobs SET status='cancelled', lease_token=lease_token+1, lease_owner=NULL, lease_expires_at=NULL,
       finished_at=now(), updated_at=now()
 WHERE id=:id AND status IN ('queued','running') RETURNING *;
```
0 rows → read the job: `cancelled` → 200, `succeeded`/`failed` → 409 with `job`, missing → 404.
- *Alternative: soft cancel (`cancel_requested` flag, worker decides).* It keeps nearly finished work, but the API can only answer 202 and the final state is unknown at response time, so it gives no crisp invariant.

### D9. Idempotent create
```sql
INSERT INTO jobs(id, idempotency_key, request_hash, payload, status)
VALUES (:id, :key, :hash, :payload, 'queued')
ON CONFLICT (idempotency_key) DO NOTHING RETURNING *;
```
Empty result → `SELECT ... WHERE idempotency_key=:key`. The same hash → 200, a different hash → 409 with `jobId`.
Canonical form: a small recursive function rebuilds the `JsonNode` with object fields sorted (`TreeMap`), and numbers are normalized through
`BigDecimal.stripTrailingZeros()`. The hash is SHA-256 over the serialized result. We do not rely on `ORDER_MAP_ENTRIES_BY_KEYS`,
because it applies to `Map`, not to `ObjectNode`.
With concurrent inserts of one key, the second waits on the unique index until the first commits and then does nothing.
In READ COMMITTED the following `SELECT` is a new statement and sees the committed row.

### D10. Isolation level: READ COMMITTED
Every transition is a single conditional `UPDATE`. In READ COMMITTED, Postgres waits for a concurrent writer of the same row
and then re-evaluates the `WHERE` clause on the newest row version, which makes each statement an atomic compare-and-set.
- *Alternative: SERIALIZABLE.* Also correct, but it adds `40001` serialization failures and retry loops with no gain for these statements.

### D11. Keyset pagination
Cursor = base64url of `createdAt|id` from the last item. Query `WHERE (created_at, id) < (:c, :id) ORDER BY created_at DESC, id DESC LIMIT :limit+1`.
The extra row tells whether a next page exists.
- *Alternative: OFFSET.* Pages shift under concurrent inserts and large offsets scan and discard rows.

### D12. Workers: one job per process (author's choice)
One `kill -9` interrupts at most one execution, which keeps chaos results easy to interpret.
Throughput is `workers / mean job duration` (about 27 jobs/s with 8 workers and 300 ms jobs, less while workers restart).
Little's law: 100 creates/s × 0.3 s needs about 30 executions in flight, so about 40 worker slots with headroom. That number goes into the verdict.
- *Alternative: a thread pool per worker.* Higher throughput, but more reasoning for each kill.

### D13. Emulated execution, failures and test hooks (author's choice)
The duration is uniform in 100–500 ms, split into ≤50 ms slices that check `attempt.stop`. Each attempt fails with probability 0.05.
Randomness comes from `SplittableRandom` seeded with `hash(job_id, lease_token)`, so a given attempt behaves the same on any worker.
With `WORKER_TEST_HOOKS=true`, the payload field `simulate` can be `crash` (`Runtime.halt(137)`) or `hang` (ignore `stop`).
The hooks exist for the poison-job and watchdog tests, and they are off in compose.

### D14. Chaos harness outside the stack (author's choice: Docker containers)
`chaos-harness` is a Java CLI that runs on the host. It talks HTTP to the API, JDBC to Postgres (read-only checks) and the `docker` CLI to the workers.
- **Fresh stack per run.** `docker compose down -v`, then `up` with the run's environment, with `QUEUE_MUTANT` set on API and workers. Then a schema check
  (does the `effects` primary key exist?) that aborts on a mismatch with the mode.
- **Pacing.** 15 creates/s by default. Duplicates are fired concurrently (same instant) or as retries after 0.2–3 s. Conflicting resends go out after the first create returns.
- **Cancels.** Half go to random jobs after a random delay. Half go to jobs seen `running` by a `GET` poller, sent 0–400 ms later.
- **Victims.** Worker id = `<HOSTNAME>-<8 hex>`, and `HOSTNAME` is the container id. The harness queries `SELECT DISTINCT lease_owner FROM jobs WHERE status='running'`
  to choose an executing victim with p=0.8.
- **Kills.** `docker kill -s KILL`, then `docker start` after 0.5–1.5 s. **Zombies.** `docker pause` for lease + 3 s, then `docker unpause`, every 15 s ± 5 s.
- **Fault window.** Faults continue until 95% of jobs are terminal. Then the harness unpauses and starts every worker and waits for quiescence.
- **Sampling.** I10 runs once per second. The other invariants run once after quiescence.
- **Client log.** JSONL next to the run log. HTTP timeout 15 s, and timeouts are logged as `unknown`.
- **Real clock skew.** For the skew run, the worker image includes `libfaketime` (a glibc-based Temurin image, not Alpine). The skew run sets
  `LD_PRELOAD` and `FAKETIME=+1h` (monotonic not faked) on half of the workers. At startup each worker logs `Instant.now()` next to `SELECT now()`.

### D15. Mutant modes
`QUEUE_MUTANT` ∈ {`none`, `naive-claim`, `no-fencing`, `worker-clock-lease`}, read at startup by API and workers and printed as a WARN banner.
The SQL variants are chosen once at startup. Only `naive-claim` changes control flow, so only it gets its own small class.
- `naive-claim`: `SELECT id ... LIMIT 1` without a lock, then `UPDATE ... WHERE id=:id` without re-checking the status. Caught by I9.
- `no-fencing`: heartbeat, completion and failure drop `AND status='running' AND lease_token=:token`. Flyway also applies
  `db/mutant/no-fencing/V100__drop_effect_pk.sql`, so zombies and cancelled workers can write. Caught by I2/I5.
- `worker-clock-lease`: lease expiry is written as worker `Instant.now() + 1h + lease` instead of DB `now() + lease`. Caught by I10 within seconds, and by I4 later.

### D16. Configuration, resources and observability
All timings come from environment variables with the defaults above. Hikari pool: 4 per worker, 10 for the API, so 42 connections, well below
`max_connections=100`. JVM flags for workers: `-Xmx256m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC` for fast restarts. `stop_grace_period: 15s`.
Logs: one line per state transition with `jobId`, `token`, `workerId`. Spring Actuator exposes health. Metrics beyond health are out of scope.

## Risks / Trade-offs

- [Postgres is a single point of failure] → Out of scope. Stated in the verdict. Recovery behaviour after a restart is argued, not tested.
- [Exactly-once effect holds only for an effect in the same database] → Stated explicitly. External effects would need idempotency at the receiver or fencing checked by the storage.
- [Recovery latency after a crash is up to lease + 2 reaper intervals (~7 s)] → Accepted for training jobs. Configurable.
- [A death counts as an attempt, so an innocent job can fail after 3 unlucky attempts] → Expected about 0.07³ × 9,000 ≈ 3 such jobs per run (5% failure + ~2% kills). That figure is written in `docs/chaos.md`. Next step: separate counters for errors and lost leases.
- [Polling cost and up to ~1 s pickup latency when idle] → Accepted. Next step: `LISTEN/NOTIFY`.
- [Table bloat: frequent `UPDATE`s on `jobs`, with heartbeats every second] → Partial indexes keep the hot set small. Retention and `fillfactor` tuning are next steps.
- [The audit trigger adds write load] → 3–4 rows per job. Acceptable here, documented as a verification tool.
- [Harness timing is not reproducible, and rare races may not appear in 10,000 jobs] → Several seeds, "teeth" counters with an inconclusive flag, mutants that must be caught, a targeted race test (task 2.5), and the limitations written down.
- [Mutant code in production code] → Off by default, explicit env var, loud banner, SQL variants chosen once.
- [Test hooks could crash workers if they were left on] → Off by default and not set in compose. The setting is logged at startup.

## Migration Plan

Greenfield: `docker compose up` creates the schema through Flyway in the `api` container. There is no data to migrate. Rollback means `docker compose down -v`.

## Open Questions

- Exact retry backoff constants, pacing rate and chaos jitter can be tuned after the first runs without changing specs or tasks.
