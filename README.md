# Training-job queue

A job queue for training jobs with several workers, built to answer one question: does it stay correct
when workers are killed with `kill -9`, freeze and come back, clients retry requests and users cancel jobs mid-run?

Java 21, Spring Boot 3, plain JDBC (`JdbcTemplate`), Postgres 16, Flyway, Docker Compose.

## Run it

```bash
docker compose up
```

This starts Postgres, the API on `http://localhost:8080` and 8 workers. The first build takes a few minutes,
because Maven downloads dependencies inside the build container. Postgres is published on host port **55432**.

Smoke test against the running stack:

```bash
./scripts/smoke.sh
```

## API

| Method | Path | Result |
|---|---|---|
| `POST` | `/jobs` with header `Idempotency-Key` and body `{"payload": {...}}` | `201` new job · `200` same key and same payload (existing job) · `409 idempotency_conflict` same key, different payload |
| `GET` | `/jobs/{id}` | `200` job · `404 not_found` |
| `GET` | `/jobs?status=&limit=&cursor=` | `{"items": [...], "nextCursor": ...}`, newest first, cursor pagination |
| `POST` | `/jobs/{id}/cancel` | `200` cancelled (also when already cancelled) · `409 job_finished` with `job` · `404` |

States: `queued → running → succeeded | failed | cancelled`. Every error has the body `{"error": "<code>", "message": "..."}`.

```bash
curl -s -X POST localhost:8080/jobs -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: demo-1' -d '{"payload":{"model":"resnet18"}}'
```

## How it works, in five lines

- **Claim:** one `UPDATE ... WHERE id = (SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1)`, so two workers never get the same job.
- **Dead worker:** 5 s lease renewed by a heartbeat every second. A reaper in every process returns expired jobs to the queue. All times come from the database clock.
- **Zombie:** every transition except success bumps a fencing token. Writes with an old token update 0 rows.
- **Guarantee:** at-least-once execution, exactly-once effect. The effect row and `succeeded` are written in one fenced transaction, and `effects.job_id` is a primary key.
- **Cancel of a running job:** immediate. It bumps the token, so the worker can no longer commit, and it stops on its next heartbeat.

The full specification, the design with rejected alternatives, and the self-check review are in
[`openspec/changes/add-job-queue-service/`](openspec/changes/add-job-queue-service/).

## Tests

```bash
mvn test
```

The tests use Testcontainers, so Docker must be running. They cover:

- claim exclusivity under 16 threads;
- fencing of stale tokens;
- the cancel/complete race (500 iterations);
- the reaper skipping rows locked by a stalled transaction;
- 50 concurrent duplicate POSTs;
- cancel during execution;
- self-fencing when the database is unreachable;
- frozen, crashing and hung workers;
- a check that lease code never reads the wall clock;
- one test per seeded bug.

## Seeded bugs (mutant modes)

`QUEUE_MUTANT` deliberately disables one protection, to prove the chaos checks can see it. Never set it in production.

| Mode | What breaks | Caught by |
|---|---|---|
| `naive-claim` | Claim reads, then updates without a lock | Audit log shows `running → running` |
| `no-fencing` | Heartbeat, complete and fail ignore the token, and the effects primary key is dropped | Duplicate effects, cancelled jobs coming back |
| `worker-clock-lease` | Lease expiry from a worker clock one hour ahead | Lease horizon check |

```bash
docker compose down -v && QUEUE_MUTANT=no-fencing docker compose up
```

## Layout

```
queue-service/   Spring Boot app: api and worker roles, SQL in JobRepository, Flyway migrations
chaos-harness/   chaos test CLI (workload, kill -9 / pause injection, invariant checks)
scripts/         smoke test
openspec/        specification, design, tasks, review
runs/            raw output of chaos runs (all of them, failed ones included)
```
