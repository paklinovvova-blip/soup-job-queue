# Proposal

## Why

We need a training-job queue that stays correct in production conditions: workers die with `kill -9`,
freeze and come back, clients retry requests, and users cancel jobs mid-run. The task explicitly says
"green happy-path tests prove nothing", so the change has to deliver the service together with evidence
(a chaos harness, seeded-bug runs, written analysis) that it keeps its guarantees under failure.

## What Changes

- New HTTP API to create a job (with an idempotency key), get its status, list jobs with cursor pagination, and cancel a job.
- Job state machine `queued → running → succeeded | failed | cancelled`, with terminal states that never change again.
- Worker process (at least 4, 8 in the chaos run) that claims jobs from Postgres, holds a lease with heartbeats,
  emulates "training" with a side effect row, retries failures up to a limit, and stops when it loses its lease.
- Dead-worker detection through lease expiry and a reaper. Zombie protection through a fencing token on every write.
- Delivery guarantee: **at-least-once execution, exactly-once effect** for the effect stored in the same database.
- Execution log that records every real run of a job, so the number of runs can be counted independently of the effect.
- Chaos harness: 10,000 jobs, 8 workers, a random worker killed with `kill -9` about every 2 seconds, zombie pauses,
  duplicate POSTs and cancellations in parallel, then an invariant check over the database and the client-side log.
- Seeded-bug (mutant) modes that deliberately break claiming and fencing, so the harness is shown to catch real bugs.
- Written deliverables: one-page design note, silent-failure analysis, raw run outputs (failed ones included),
  SHIP/DON'T SHIP verdict at 100 RPS backed by a load run, AI usage note.

## Capabilities

### New Capabilities

- `job-api`: Public HTTP contract for jobs: idempotent creation, status, cursor-paginated listing, cancellation, error codes.
- `job-execution`: How jobs are claimed, leased, executed, retried, reaped, fenced and completed by workers, and which delivery guarantee holds.
- `chaos-verification`: The chaos harness, the invariants it checks, the mutant modes, and the format of the evidence it produces.

### Modified Capabilities

None. The project starts empty.

## Impact

- New code: `queue-service` (Spring Boot app with `api` and `worker` roles), `chaos-harness` (Java CLI).
- New database schema in Postgres 16 (tables `jobs`, `executions`, `effects`) managed by Flyway.
- New runtime: `docker-compose.yml` with Postgres, 1 API container and 8 worker replicas.
  The harness talks to the Docker daemon (`docker kill`, `docker pause`, `docker start`).
- New documents: `docs/design.md`, `docs/silent-failures.md`, `docs/verdict.md`, `runs/` with raw outputs, `AI_USAGE.md`.
- Not in scope: authentication, multi-tenant key scoping, Postgres high availability, real training workloads.
