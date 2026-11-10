# AI usage

The task allows AI tools and asks where they were used, which suggestions were accepted,
and what was verified or redone by hand. This file is that record. It is a living document:
it is updated at each stage, not written after the fact.

## How we worked

I used Claude Code (Claude desktop app, Code tab) as a design partner and pair programmer.
I set the working format up front:

1. **Discussion first, code later.** For every design question the AI had to lay out the options,
   the trade-offs and a recommendation, explained in plain words. I made the decision.
   Nothing went into the spec that I could not explain myself.
2. **Spec-driven development with OpenSpec.** We wrote the full specification before any code
   and put it into [OpenSpec](https://github.com/Fission-AI/OpenSpec) (`openspec/` folder):
   requirements, scenarios and invariants. The implementation is then built and checked against
   that spec, step by step.
3. **Evidence over claims.** Any statement the AI makes about the system's behaviour (for example
   "SKIP LOCKED prevents double claims") counts as a hypothesis until a test or a chaos run confirms it.

## Tools and skills

| Tool | What it was used for |
|---|---|
| Claude Code (Claude Opus 5.5) | Design discussion, spec drafting, code generation, review |
| OpenSpec CLI, pinned to `1.15.0` | Structure of the spec (`openspec/specs`, `openspec/changes`), `openspec validate --strict` |
| OpenSpec skill `openspec-propose` | Generated the change `add-job-queue-service`: proposal, three capability specs, design, tasks |
| `system-design-skills` plugin | System-design reference (task scheduling, resilience, consistency). Enabled after the first design discussion |
| `Java Backend Skills` plugin | Java concurrency, Spring, testing reference. Enabled for the implementation phase |

The plugins were enabled after the base design was already drafted, so the first design pass
did not use them. Later stages note in the log below when a skill was used.

### Self-check review with skills

Before implementation, the full spec was reviewed in a separate Claude Code session that had the review skills loaded:
`system-design-skills` (task-scheduling, resilience-failure, consistency-coordination, data-storage) and
`deep-review` (severity rubric, "challenge every finding"). Arxitect was skipped because there was no code yet.
The review is kept as is in `openspec/changes/add-job-queue-service/REVIEW.md`:
3 blockers, 13 should-fix, 13 nice-to-have.

The review found that the core concurrency design held, but the **evidence layer** was weak. All findings were applied to the spec. The most important ones:

| Finding | What was wrong | Change |
|---|---|---|
| B1 | The `naive-claim` mutant was masked by fencing: a double claim leaves no double effect, so I2/I3/I7 would stay green | New audit table filled by a DB trigger, and invariant I9 "only legal transitions" (`running → running` = double claim) |
| B2 | Faults stopped when submissions ended, so most execution ran fault-free and cancels almost never hit running jobs | Paced submissions (15/s), faults until 95% of jobs are terminal, half of the cancels targeted at running jobs, inconclusive flag |
| B3 | The `worker-clock-lease` mutant would be either masked or caught only by a 15-minute timeout | Skew fixed at +1 h, sampled invariant I10 "lease horizon" |
| S1 | The multi-row reaper could block on a paused worker's row lock | Batched reaper with `SKIP LOCKED`, `idle_in_transaction_session_timeout=2s` |
| S2 | One exception silently kills a Java scheduled timer forever | `try/catch` in every periodic task, monotonic self-fencing in the heartbeat |
| S4 | A hung execution frees the job but leaves the worker stuck | Watchdog: exit the process if a stop is not honoured in 5 s |
| S5 | A clock-skew test through an injected `Clock` proves little | Real process skew via `libfaketime` (stretch), plus a static check for wall-clock calls |
| S8 | The 100 RPS verdict had no fixed request mix or criteria | Mix and SHIP criteria pre-registered in the spec, plus Little's-law capacity math |
| S13 | Scope too large for 4–6 h | Core path and **(stretch)** items marked in `tasks.md` |

## Decision log

### Decisions I made myself (format and scope)

| # | Decision | Why | Consequence |
|---|---|---|---|
| D1 | **Java** as the stack | The language I want to be evaluated in and can defend line by line | Java 21 + Spring Boot 3. Plain `JdbcTemplate` instead of JPA, so every SQL statement that carries correctness is visible in the code |
| D2 | **Spec first, via OpenSpec** | The task says "design before code". A written spec makes it checkable | More time upfront. The harness invariants come directly from spec scenarios |
| D3 | **Discussion format**: AI proposes options, I choose | I need to understand and own every decision | Each choice below has the alternatives and the reasons recorded |
| D4 | **Separate AI_USAGE.md** that I review and edit | The task asks for it explicitly | AI use is documented here and not scattered across the code |

### Design forks: options from the AI, choice by me

For each fork the AI gave two options with trade-offs and marked one as recommended.
In all four cases I chose the recommended option, for the reasons below.

| # | Question | Options | My choice | Consequence I accept |
|---|---|---|---|---|
| D5 | Cancelling a **running** job | (a) Cancel immediately and revoke the lease (bump `lease_token`). (b) Soft cancel: set a flag, the worker stops on its own and may still finish as `succeeded` | **(a)** | Crisp invariant: if the API answered `cancelled`, the effect never happens. Cost: work in progress is thrown away even if it was 99% done. The worker learns about the cancel on its next heartbeat |
| D6 | Jobs per worker process | (a) 1 job per process. (b) Thread pool per process | **(a)** | One `kill -9` interrupts exactly one job, which makes the chaos results easy to reason about. Cost: lower throughput (8 workers = 8 jobs in flight). Moving to (b) is a known next step |
| D7 | Random execution failures | (a) About 5% of executions fail, with retries up to `max_attempts=3`, then `failed`. (b) Jobs only fail through kills | **(a)** | The `failed` state and the retry path get exercised under chaos, not only the happy path. The harness must tell "failed after 3 attempts" apart from "lost" |
| D8 | How the chaos harness kills workers | (a) Docker containers, `docker kill -s KILL`, `docker pause` for zombies. (b) JVM subprocesses killed with `destroyForcibly` | **(a)** | Closer to a real deployment and to the required `docker compose up`. Cost: slower restarts, and the harness has to talk to the Docker daemon |

### AI proposals I accepted for the base architecture

These came from the AI in the first design discussion. I accepted them after the trade-offs
were explained. How each one is verified is listed next to it.

| Proposal | How it is verified |
|---|---|
| Postgres instead of SQLite (row locks, many writers from separate containers) | Chaos run with 8 workers in separate containers |
| Claim with a single `UPDATE ... WHERE id = (SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1)` | Chaos invariant "at most one effect per job" plus a mutation run with a deliberately broken claim |
| Lease + heartbeat + reaper. All time comes from Postgres `now()`, never from worker clocks | Kill tests, plus a test with a skewed worker clock |
| Fencing token (`lease_token`) checked on heartbeat, complete and fail | `docker pause` zombie test. Mutation run with fencing disabled |
| Effect row and `succeeded` transition in one transaction, plus a unique index on `effects(job_id)` | Invariants: `succeeded` ⇔ exactly one effect, `cancelled` ⇒ no effect |
| Idempotency through a unique key and `INSERT ... ON CONFLICT`, 409 for a reused key with a different body | Parallel duplicate POSTs in the harness |
| Keyset (cursor) pagination instead of OFFSET | API test: insert rows between page requests |
| Mutation testing of the harness itself (it must catch seeded bugs) | Recorded runs with each seeded bug, expected result: red |

## Where the AI was unsure or needed correction

- **Docker restart policy after `docker kill`.** The AI was not sure whether `restart:` brings a
  container back after `docker kill`. Decision: the harness restarts killed containers explicitly
  and does not rely on the restart policy. **Checked later on the real stack:** 5 s after
  `docker kill -s KILL`, a worker with `restart: unless-stopped` was still `exited` with `RestartCount=0`.
  Docker treats `kill` as a manual stop, so relying on the policy would have left the fleet shrinking during the chaos run.
- **OpenSpec version.** The newest npm release (`1.17.0`) was published one day before we started.
  We pinned `1.15.0`, which was released more than two weeks earlier, so a fresh and possibly broken release
  would not affect the project.

- **First draft of the `no-fencing` mutant was too weak.** It removed the token check only from completion.
  But the heartbeat was still fenced, so a resumed zombie would usually stop on its heartbeat before
  committing, and the mutant could pass the harness for the wrong reason. Fixed: the mutant removes fencing from heartbeat,
  completion and failure.
- **Invariant I8 was worded inconsistently** between the spec and the notes ("failed used all attempts *or* got an error").
  In the design a job becomes `failed` only after all attempts, so the wording was tightened.
- **`openspec validate --strict` flagged a requirement over 500 characters.** It was split into two requirements, each with its own scenarios.

- **AI-written SQL had a whitespace bug that the tests caught.** A Java text block strips trailing spaces,
  so `"RETURNING """ + columns` produced `RETURNINGid`. 20 repository tests failed on the first run. Fixed with a leading space and a comment.
- **AI-written smoke script hung.** A bare `wait` in bash waits for *all* background jobs, including the Java
  worker processes, so the script never reached the `kill -9`. Rewritten to wait only for the curl requests.
- **The first live kill -9 smoke did not test what it was meant to test.** The killed worker's job happened to
  be one of the jobs the script then cancelled, so the reaper never had to pick it up (audit log: `running → cancelled`, 1 row).
  The run was "green", but it did not exercise the reaper. Re-run without cancels: the reaper returned the job and all 300 jobs succeeded with 300 effects.
  This is the "green does not mean tested" lesson from the task, seen on our own run.
- **Cancels in a deep backlog hit only queued jobs.** In the same smoke run, all 20 cancels landed on queued jobs.
  This confirms review finding B2 in practice. The chaos harness targets half of its cancels at running jobs for this reason.

- **AI-written test helper leaked connections.** Each throwaway test database opened a pool of 20 that was
  never closed, and Postgres refused new clients (`too many clients already`). Fixed: small pools, closed after each test.

More entries are added as they come up during implementation.

## Verification log

What I checked, changed or redid myself. Filled in during implementation.

| Date | What | Result |
|---|---|---|
| | | |
