# Tasks

Tasks marked **(stretch)** are done only after every core task in groups 1–9 is complete.

## 1. Project scaffolding

- [x] 1.1 With the author's explicit approval, run `git init` and add a `.gitignore` for Maven, IntelliJ (`.idea/`), macOS (`.DS_Store`) and `runs/*.jsonl` scratch files; verify `git status` shows only intended files
- [x] 1.2 Create the Maven parent `pom.xml` (Java 21) with modules `queue-service` and `chaos-harness`; verify `mvn -q -DskipTests package` succeeds
- [x] 1.3 Add Spring Boot 3 (web, jdbc, actuator), Flyway, Postgres driver, Jackson and Testcontainers to `queue-service`; verify the app starts against a local Postgres and `/actuator/health` returns UP
- [x] 1.4 Add `docker-compose.yml` with only `postgres:16` (named volume, healthcheck); verify `docker compose up postgres` reports healthy

## 2. Schema and data access

- [x] 2.1 Write `V1__init.sql` with `jobs`, `executions`, `effects`, `job_transitions`, the audit trigger and the indexes from design D4; verify in a Testcontainers test that Flyway applies it, the `effects` primary key exists, and a status change writes one `job_transitions` row while a heartbeat writes none
- [x] 2.2 Implement the job repository with the claim, execution record, heartbeat, complete, fail, batched reap, cancel, create and list statements from design D5–D11 using `JdbcTemplate`, with `idle_in_transaction_session_timeout=2s` on the pool; verify each method with an integration test against Testcontainers Postgres
- [x] 2.3 Add claim concurrency tests: 16 threads claim from 1,000 queued jobs and every job is claimed exactly once, 20 runs in a row; and while one transaction holds `FOR UPDATE` on the oldest job, a claim returns the next job without waiting
- [x] 2.4 Add fencing tests: completion, failure and heartbeat with a stale token update 0 rows and leave no effect; a second effect insert fails with a uniqueness violation; the token increases on claim, reap, retry, failure and cancel, and stays the same on success
- [x] 2.5 Add a race test: cancel and complete fire on the same running job from two threads 500 times; verify that every time exactly one wins, a cancelled job has no effect, and the audit log shows only legal edges
- [x] 2.6 Add a reaper test: with one expired job row-locked by an open transaction, the reaper still reaps the other expired jobs in the same tick, and that open transaction is aborted after about 2 s of inactivity

## 3. Public API

- [x] 3.1 Implement canonical JSON (recursive key sort, `BigDecimal.stripTrailingZeros`) and SHA-256 hashing; verify unit tests: key order and `1` vs `1.0` give equal hashes, while a different value gives a different hash
- [x] 3.2 Implement `POST /jobs` with `Idempotency-Key`, the 16 KiB payload limit, 201/200/409 (`jobId`)/400 responses and the `Location` header; verify the `job-api` create scenarios with MockMvc plus Testcontainers
- [x] 3.3 Add a concurrent duplicate test: 50 parallel POSTs with one key give exactly one 201, 49 times 200 and a single job id
- [x] 3.4 Implement `GET /jobs/{id}` and the error format (`not_found`, `invalid_request`); verify the 200/404/400 scenarios
- [x] 3.5 Implement `GET /jobs` with keyset cursor, `limit` 1–100, `status` filter and parameter validation; verify a full page walk with concurrent inserts shows every pre-existing job exactly once
- [x] 3.6 Implement `POST /jobs/{id}/cancel` with 200, 409 (with `job`) and 404 semantics from design D8; verify the cancel scenarios, including cancelling twice and cancelling a finished job

## 4. Worker

- [x] 4.1 Implement the worker loop: worker id `<HOSTNAME>-<8 hex>` per process start, claim, execution record (no execution if it fails), immediate re-claim after a job, idle backoff 100 ms → 1 s; verify that 200 jobs all reach a terminal state and that two starts get different ids with the same prefix
- [x] 4.2 Implement the `Attempt` object, emulated execution in ≤50 ms slices checking `attempt.stop`, a uniform 100–500 ms duration and 5% failures seeded by `(job_id, lease_token)`; verify by unit test that the failure rate over 10,000 seeds is 5% ± 1%
- [x] 4.3 Implement the per-attempt heartbeat (1 s), stop on 0 rows, retry on exception, monotonic self-fencing after `lease − heartbeat`, and the `claimed_at` max-runtime guard; verify that a job cancelled mid-run stops its worker within 1.5 s with no effect, and that a heartbeat failing for 5 s stops the execution
- [x] 4.4 Implement fenced completion and failure with backoff, token bump on failure, and outcome recording; verify that 3 forced failures end in `failed` with `lastError`, and that a retried job is not claimed before its `run_after`
- [x] 4.5 Implement the reaper in both roles (1 s, batched, `SKIP LOCKED`, `try/catch`); verify that a job whose worker is frozen is requeued between 4 s and 7.5 s with a new token and has 2 execution rows and 1 effect after it finishes; verify that one reaper tick that throws does not stop the next
- [x] 4.6 Implement the test hooks (`WORKER_TEST_HOOKS`, `simulate=crash|hang`) and the stop watchdog (exit after 5 s); verify that a `crash` job ends `failed` after 3 claims, that a `hang` job with `maxRuntime=2s` is reaped with a new token while its worker exits, and that hooks are ignored when disabled
- [x] 4.7 Add a static check test: lease, reaper, heartbeat and backoff code contain no `Instant.now`, `System.currentTimeMillis` or `LocalDateTime.now`, except in the `worker-clock-lease` mutant class; verify that the test fails if such a call is added
- [ ] 4.8 **(stretch)** Implement graceful shutdown on SIGTERM (stop claiming, 10 s grace, `stop_grace_period: 15s`); verify that a short in-flight job completes and no new job is claimed

## 5. Mutant modes

- [x] 5.1 Add the `QUEUE_MUTANT` setting for API and worker, a WARN startup banner, the `naive-claim` class, the `no-fencing` SQL variants with their Flyway location, and the `worker-clock-lease` variant; verify the default is `none` and the banner prints for every other value
- [x] 5.2 Add one targeted test per mutant: `naive-claim` produces a `running → running` audit row under concurrent claims, `no-fencing` lets a stale token write a second effect, and `worker-clock-lease` writes a lease more than 1 h ahead; verify each test fails with the mutant on and passes with it off

## 6. Containers

- [x] 6.1 Write a multi-stage `Dockerfile` for `queue-service` (Maven build, glibc-based Temurin JRE 21 with `libfaketime` installed, worker JVM flags from D16); verify the image builds and starts in both roles
- [x] 6.2 Extend `docker-compose.yml` with `api` (port 8080, runs migrations) and `worker` (`deploy.replicas: 8`, `restart: unless-stopped`, waits for the schema), passing all timings, `QUEUE_MUTANT` and pool sizes as environment variables; verify a clean `docker compose up` gives 1 API and 8 workers
- [x] 6.3 Add `scripts/smoke.sh` that creates, polls, lists and cancels jobs with `curl`; verify that on a fresh stack it ends with a succeeded and a cancelled job
- [x] 6.4 Write `README.md` with how to run the stack, the smoke test, the tests and the harness; verify each documented command runs as written

## 7. Chaos harness

- [ ] 7.1 Create the `chaos-harness` CLI with config flags (jobs, rate, workers, seed, mode, timeouts), fresh-stack setup (`down -v`, `up` with the run environment on API and workers) and a fatal schema-vs-mode check; verify `--help`, a dry run with 10 jobs, and an abort on a deliberately wrong schema
- [ ] 7.2 Implement the seeded, paced workload (15 creates/s): ≥30% duplicates (concurrent and delayed), ~1% conflicting payloads, ~10% cancels with half targeted at running jobs, 15 s request timeout and a JSONL client log with `unknown` for timeouts; verify that the same seed generates an identical plan
- [ ] 7.3 Implement the fault injector: SIGKILL every 2 s ± jitter with victims chosen among executing workers with p=0.8, restart after 0.5–1.5 s, a pause of an executing worker for lease + 3 s every 15 s ± 5 s, a fault window until 95% of jobs are terminal, then unpause and start all; verify on a 1,000-job run that the kill and pause rates and the "victim was executing" share are as expected
- [ ] 7.4 Implement quiescence with a 15-minute timeout, invariants I1–I9 after quiescence, I10 sampled every second, and offending ids in the report; verify by corrupting a test database by hand (duplicate effect, stuck job, illegal audit row, far-future lease) and checking each is reported
- [ ] 7.5 Implement the teeth counters, the inconclusive rule (exit 2), and the raw output at `runs/<UTC>-<mode>-seed<seed>.log` with config, revision, counters, invariants, expectation and exit code, without ever overwriting a file; verify that a failed run keeps its file
- [ ] 7.6 Implement the load mode with the fixed mix (20/60/15/5), the SHIP criteria from the spec, p50/p95/p99, error rate and queue depth samples, plus a 100%-create variant; verify a 30-second run at 20 RPS produces the report

## 8. Evidence runs

- [ ] 8.1 Run the full chaos test (10,000 jobs, 8 workers) in `none` mode with at least 3 different seeds; verify that the logs are in `runs/` and that failures are investigated and kept, not rerun until green
- [ ] 8.2 Run `naive-claim` and `no-fencing` at least once each; verify that each run reports "expected failure: caught", or record honestly that it was not caught and why
- [ ] 8.3 Run the load mode at 100 RPS with the fixed mix for 5 minutes, and the 100-creates/s variant; verify that both reports are saved in `runs/`
- [ ] 8.4 **(stretch)** Run `worker-clock-lease`; verify that I10 catches it and the log is kept
- [ ] 8.5 **(stretch)** Run the real clock-skew chaos run (libfaketime +1 h on half the workers); verify that the log shows the skew and all invariants pass

## 9. Written deliverables

- [ ] 9.1 Write the one-page `docs/design.md`: claiming, dead-worker detection, cancel of a running job, the guarantee and why; verify it fits on one page and matches the code
- [ ] 9.2 Write `docs/silent-failures.md`: double claim, zombie, cancel/complete race, request retry, isolation level, clock skew, silent timer death, hung execution, each with the protection and the exact test or run that checks it, and note that fencing is the second line of defense behind I9 for double claims; verify every reference points to an existing test or log
- [ ] 9.3 Write `docs/chaos.md`: how to run the harness, the number of runs, a summary table linking every raw log, the expected ~3 innocent `failed` jobs per run, and what the harness does NOT catch (API and Postgres crashes, network faults, DB clock jumps, the ~1 ms commit race left to task 2.5, external effects); verify each listed log exists
- [ ] 9.4 Write `docs/verdict.md`: SHIP or DON'T SHIP at 100 RPS against the pre-registered criteria, the Little's-law capacity math for 100 creates/s, and what to fix first; verify the numbers match the saved reports
- [ ] 9.5 Add the paragraph on what surprised the author and what still concerns them, and update `AI_USAGE.md` with skills used, accepted suggestions and the verification log; verify the author reviewed and edited both

## Workflow follow-up

- Archive the change with `openspec archive add-job-queue-service` after implementation and review, so the specs move to `openspec/specs/`.
- Ask the author before any commit, push or GitHub action.
- Run the Arxitect architecture review once groups 2–4 have landed, as `REVIEW.md` suggests.
