# Design note

A training-job queue: an HTTP API and N worker processes on one Postgres 16. Every state transition is one
conditional SQL statement, a compare-and-set on the job row. The full design with rejected alternatives is in
[`openspec/changes/add-job-queue-service/design.md`](../openspec/changes/add-job-queue-service/design.md).

**Why Postgres, not SQLite.** Eight workers in separate containers write at the same time. Postgres has row locks,
`FOR UPDATE SKIP LOCKED` and one shared clock (`now()`). SQLite has a single writer per database file, no `SKIP LOCKED`,
and a file shared between containers. SQLite would only fit workers as threads of one process, and then `kill -9` takes all of them down at once.

## How a worker claims a job

```sql
UPDATE jobs SET status='running', lease_token=lease_token+1, lease_owner=:worker,
       lease_expires_at=now()+:lease, attempts=attempts+1, claimed_at=now()
 WHERE id = (SELECT id FROM jobs WHERE status='queued' AND run_after<=now()
             ORDER BY run_after, created_at FOR UPDATE SKIP LOCKED LIMIT 1)
RETURNING id, lease_token, payload;
```

Choosing and taking the job happen in one statement. The subquery locks one row and *skips* rows that other
workers have locked, so two workers never get the same job and never wait for each other. The returned
`lease_token` is the worker's **fencing token** for this attempt.

## How a dead worker is detected

The lease lasts 5 s, and the worker renews it with a heartbeat every second, but only while
`status='running' AND lease_token=:mine`. A reaper runs every second in the API and in every worker. It returns jobs whose lease
expired by **database time** to `queued`, or marks them `failed` after 3 attempts, and bumps the token. A killed
worker's job is back in the queue within about 7 s. A death counts as an attempt, so a job that crashes every worker
ends `failed` instead of looping forever. More details:

- **A hung execution** behind a live heartbeat thread is bounded by a 60 s max runtime. A watchdog exits the process if a stop request is ignored for 5 s.
- **Self-fencing.** If the heartbeat itself fails, for example because the database is unreachable, the worker stops on its own after `lease − 1 s` by the monotonic clock.
- **Clock skew between workers is irrelevant.** No worker compares its own wall clock with anything. Lease, reap, backoff and max runtime all use `now()` of Postgres.

## What happens when a running job is cancelled

Cancel is immediate: `UPDATE ... SET status='cancelled', lease_token=lease_token+1 WHERE status IN ('queued','running')`.
The bumped token revokes the worker's lease. Its next heartbeat (≤ 1 s) updates 0 rows and it stops, and its commit, if it
gets there first, also updates 0 rows. A cancel racing with completion is decided by the row lock: the first commit wins, and
the loser re-checks its `WHERE` and changes nothing. Contract: **200 `cancelled` ⇒ the job never has an effect.
409 `job_finished` ⇒ the job keeps the state in the response.** The cost is that work done before the cancel is thrown away.
The rejected alternative was a soft-cancel flag, which only allows a 202 "maybe".

## Which guarantee, and why

**At-least-once execution, exactly-once effect.** A worker can die after starting a job, and from the outside
"dead" cannot be told apart from "slow". So a job may *run* more than once: every run is a row in `executions`.
The effect (a row in `effects`) is written in the same transaction as `running → succeeded`, guarded by
`status='running' AND lease_token=:mine`. A zombie with an old token writes nothing. `effects.job_id` is the
primary key, a second line of defence that turns a bug into an error instead of a silent duplicate.
At-most-once was rejected, because losing a training job is worse than running it twice.

**The limit of this guarantee.** It holds only because the effect lives in the same database as the job state. An
external effect, such as a model file in S3 or a GPU run, cannot join the transaction. Then the guarantee is at-least-once, and the
receiver must be idempotent (keyed by job id) or check the fencing token itself.

## Proving it

Every transition is also written by a database trigger to `job_transitions`, so a test can see illegal edges. The most
important one is `running → running`, a double claim, which fencing otherwise *hides*. A chaos harness checks these invariants:

- **during the run**: 10,000 jobs, 8 workers, `kill -9` every ~2 s, pauses past the lease, duplicate POSTs and cancels;
- **after the run**: ten invariants.

Three seeded-bug modes must make it fail. See [`chaos.md`](chaos.md), [`silent-failures.md`](silent-failures.md) and [`verdict.md`](verdict.md).
