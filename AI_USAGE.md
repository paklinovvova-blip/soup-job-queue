# AI usage

The task allows AI tools and asks where they were used, which suggestions were accepted, and what was verified,
changed or redone. This file answers all four questions.

**In short:** I used Claude Code as a design partner and pair programmer for the whole project. The AI proposed designs and
wrote most of the code and documents. I set the process, made the design decisions, and asked for an independent review
before any code was written. Every claim the AI made about the system had to be backed by a test or a chaos run.
The AI made a number of mistakes along the way. Most of them were caught by tests and by the harness's own checks;
they are listed below, because they say more about the result than the successes do.

## How we worked

1. **Discussion before code.** For every design question the AI laid out the options with trade-offs and a
   recommendation. I chose. Each choice is recorded below with its consequences.
2. **Spec-driven development.** The full specification was written before any code, in
   [OpenSpec](https://github.com/Fission-AI/OpenSpec) format: requirements, testable scenarios and invariants (`openspec/`).
   The code was then built task by task from `tasks.md`, and every task names how its completion is verified.
3. **Independent self-check.** Before implementation I had the spec reviewed in a separate session, with
   system-design and review skills loaded. Its findings changed the design (see below).
4. **Evidence over claims.** A statement like "SKIP LOCKED prevents double claims" counted as a hypothesis until a
   test, a seeded-bug run or a chaos run confirmed it. Failed and inconclusive runs are kept in `runs/`.
5. **I approved every commit and push**, one at a time.

## Tools

| Tool | Used for |
|---|---|
| Claude Code (Claude Opus 5.5), desktop app | Design discussion, spec, code, tests, harness, documents |
| OpenSpec CLI `1.15.0` (pinned) and its `openspec-propose` skill | Structure of the spec and `openspec validate --strict` |
| `system-design-skills` plugin: task-scheduling, resilience-failure, consistency-coordination, data-storage | Self-check review of the spec |
| `deep-review` plugin | Severity rubric and "challenge every finding" rule for that review |
| `Java Backend Skills` plugin | Enabled for the implementation phase |

OpenSpec was pinned to `1.15.0` on purpose. The newest release (`1.17.0`) had been published one day before we started.

## Decisions

### Process and scope: mine

| # | Decision | Consequence |
|---|---|---|
| D1 | Java 21 + Spring Boot 3 | Plain `JdbcTemplate` instead of JPA, so every SQL statement that carries correctness is visible and explainable |
| D2 | Spec first, in OpenSpec | More time up front. The harness invariants come directly from spec scenarios |
| D3 | AI proposes options, I choose | Every design choice is recorded with its alternatives |
| D4 | A self-check review with skills before coding | 3 blockers were found in the evidence plan before any code existed |

### Design forks: options from the AI, choice mine

In each fork the AI marked one option as recommended. I chose that option every time, for the reasons below.

| # | Question | Choice | Alternative | Consequence I accepted |
|---|---|---|---|---|
| D5 | Cancel of a **running** job | Immediate: status `cancelled` and the lease revoked (token bump) | Soft cancel: a flag the worker checks; it may still finish | Clear contract: a 200 `cancelled` means the job never has an effect. Work in progress is thrown away |
| D6 | Jobs per worker process | One | A thread pool | One `kill -9` interrupts one job, so chaos results are easy to reason about. Lower throughput, which became the first item of the verdict |
| D7 | Emulated failures | 5% random failures, 3 attempts | Failures only through kills | The retry and `failed` paths are tested under chaos, not only the happy path |
| D8 | How chaos kills workers | Docker containers: `docker kill -s KILL`, `docker pause` | JVM subprocesses | Closer to a real deployment. Slower restarts |

### Architecture: proposed by the AI, accepted by me

| Proposal | How it was verified |
|---|---|
| Postgres instead of SQLite | 8 worker containers writing in parallel in every chaos run |
| Claim with one `UPDATE ... WHERE id = (SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1)` | Concurrency test (16 threads); the `naive-claim` mutant caught by I9 with 357 double claims |
| Lease + heartbeat + reaper, all times from Postgres `now()` | Kill and frozen-worker tests; a static test against wall-clock reads; I10 |
| Fencing token on heartbeat, complete and fail | Zombie tests; 34–39 fenced zombies per chaos run; the `no-fencing` mutant caught (28 double effects) |
| Effect and `succeeded` in one transaction; `effects.job_id` as primary key | I2, I3 and I7 in every chaos run |
| `Idempotency-Key` with `INSERT ... ON CONFLICT`, 409 for a different payload | 50 concurrent POSTs in a test; about 2,300 concurrent duplicates per chaos run |
| Keyset pagination | Page walk while inserting |
| Seeded-bug (mutant) runs of the harness itself | Both mutants caught in 10,000-job runs |

## The independent review

A separate session with the review skills loaded read the whole spec and wrote
[`REVIEW.md`](openspec/changes/add-job-queue-service/REVIEW.md), kept unchanged: 3 blockers, 13 should-fix, 13 nice-to-have.
Its conclusion was that the concurrency design held, but **the evidence would not have proven it**. All findings were applied. The most important ones:

| Finding | Problem | Change |
|---|---|---|
| B1 | A double claim leaves no double effect, because fencing hides it, so the planned invariants could not catch the `naive-claim` mutant | A trigger-written transition log and invariant I9 (`running → running`). Confirmed later: in the real mutant run I2 and I3 stayed green, and only I9 caught it |
| B2 | Faults stopped when submissions ended, and cancels almost never hit running jobs | Paced submissions, faults until 95% of jobs are terminal, targeted cancels, the "inconclusive" verdict |
| B3 | The clock mutant could only be caught by a 15-minute timeout | Sampled invariant I10 |
| S1, S2 | The reaper could block on a paused worker's lock; one exception silently kills a Java timer forever | Batched reaper with `SKIP LOCKED`; idle-transaction timeout; `try/catch` in every periodic task; self-fencing |
| S8 | The verdict had no fixed criteria | Request mix and SHIP criteria written into the spec before the load runs |

## Where the AI was wrong, and what caught it

| Mistake | Caught by | Fix |
|---|---|---|
| Too weak a first draft of the `no-fencing` mutant: the heartbeat was still fenced, so the mutant could pass for the wrong reason | Re-reading the spec before implementation | The mutant removes fencing from heartbeat, complete and fail |
| SQL built from a Java text block lost a trailing space: `RETURNINGid` | 20 failing repository tests on the first run | Leading space plus a comment |
| A test helper leaked connection pools until Postgres refused clients | A test error (`too many clients`) | Small pools, closed after each test |
| A smoke script hung: a bare `wait` also waited for the worker JVMs | The script never reached its `kill -9` | Wait only for the HTTP requests |
| The first live `kill -9` smoke was green but **never exercised the reaper**: the killed worker's job happened to be cancelled by the same script | Reading the transition log instead of the totals (`running → cancelled`, 1 row) | Re-run without cancels: the reaper returned the job, 300 of 300 jobs succeeded with 300 effects |
| The first zombie injection created **no zombies**: `docker pause` lands 100–300 ms after the victim is chosen, and the job was already over | The harness's own rule: 0 fenced executions means INCONCLUSIVE | Re-check after pausing; a pause that missed is undone and retried. Next run: 4 pauses, 4 fenced zombies |
| Unsure whether Docker restarts a container after `docker kill` | Checked on the stack: `exited`, `RestartCount=0` | The harness restarts killed workers itself |
| Percentages printed as `31,9%` (JVM locale) | Reading the dev log | `Locale.ROOT` |

## What the AI wrote

- **Code:** the service, tests, harness, Dockerfile, compose file and scripts.
- **Documents:** the spec, `docs/design.md`, `docs/chaos.md`, `docs/silent-failures.md` and `docs/verdict.md`. All numbers in the documents were taken from the run logs, and each document links its logs.
- **The verdict and the "what surprised me / what still concerns me" paragraph** were written by the AI at my request.
  The reading of "100 requests per second" as 100 new jobs per second is the AI's recommendation, which I accepted. The other reading was measured too, and both results are in the verdict.
- **A private study guide** explaining every mechanism, decision and number, for my own preparation. It is not part of the repository.

## How the result was verified

- **89 automated tests on a real Postgres** (Testcontainers): 77 in the service and 12 in the harness. Every repository statement, the claim race (16 threads, 20 repetitions), the cancel/complete race (500 concurrent pairs), the API contract, cancel during execution, self-fencing during a database outage, frozen, crashing and hung workers, one test per seeded bug, and the invariant checker against a hand-corrupted database.
- **12 harness runs**, all kept in `runs/`: 3 normal 10,000-job runs (PASS), 2 seeded-bug runs (both caught), 2 load runs, and 5 development runs, including the inconclusive ones and one aborted on a schema mismatch.
- **Manual checks on the real stack:** `docker compose up` from scratch, the smoke script, the mutant mode via compose, and `docker kill` and `docker pause` of worker containers.
- **Not verified:** the stretch items (graceful shutdown on SIGTERM, the `worker-clock-lease` chaos run, a real clock-skew run with `libfaketime`), and everything listed under "What the harness does NOT catch" in [`docs/chaos.md`](docs/chaos.md).
