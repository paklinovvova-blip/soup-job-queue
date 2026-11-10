# Chaos test: how it works, every run, and what it does not catch

## Run it

```bash
mvn -q -DskipTests package
java -jar chaos-harness/target/chaos-harness.jar --jobs 10000 --seed 1          # one chaos run
./scripts/evidence-runs.sh                                                      # every evidence run below, about 70 min
```

The harness runs on the host. It talks HTTP to the API, JDBC (read-only) to Postgres, and the `docker` CLI to the workers.
Each run goes through these steps:

1. Recreate the stack with `docker compose down -v` and `up`, with `QUEUE_MUTANT` set on the API and the workers. **Abort** if the schema does not match the mode: the effects primary key must exist, except in `no-fencing`.
2. Send 10,000 distinct keys at **15 per second**. That is below the drain rate of about 27 jobs/s, so faults overlap with execution for the whole run:
   - about 15% of keys get 2–3 **concurrent** creates;
   - about 17% get 1–2 **delayed retries** (0.2–3 s);
   - about 1% are resent with a **different payload**, which must get 409;
   - about 5% get a **random cancel** (0–3 s after create);
   - about 5% get a **targeted cancel**, sent 0–400 ms after the job is seen `running` in the database.
3. Inject faults until 95% of jobs are terminal:
   - **SIGKILL** a worker about every 2 s (±30%), then `docker start` it after 0.5–1.5 s. Docker does not restart a container after `docker kill` on its own (we checked);
   - **pause** a worker for lease + 3 s = 8 s about every 15 s (±33%). With probability 0.8 the victim is a worker that owns a running job, and a pause that lands on an idle worker is undone and retried.
4. Unpause and start every worker, then wait until nothing is `queued` or `running`, for at most 15 min.
5. Check the invariants, print the counters, give the verdict and exit: **0** expectation met, **2** inconclusive, **1** violation or mutant not caught.

The raw output goes to `runs/<UTC>-<mode>-seed<seed>.log` and is never overwritten. The full client log of every request and response
goes to a `.jsonl` next to it, which is not committed (about 3 MB per run).

## Invariants

| # | Invariant | Source |
|---|---|---|
| I1 | Every accepted key (any 200/201) has exactly one job; no job exists for a key we never sent | client log + DB |
| I2 | At most one effect per job | DB |
| I3 | `succeeded` ⇔ exactly one effect; `failed` and `cancelled` have none | DB |
| I4 | Nothing left `queued` or `running` after quiescence | DB |
| I5 | Every cancel response matches the final state: 200 ⇒ `cancelled` with no effect, 409 ⇒ the state in the response | client log + DB |
| I6 | Every response for one key names the same job (409 conflicts included), and it is the job in the DB | client log + DB |
| I7 | The effect's token equals the job's final token and matches an execution with outcome `succeeded`; a cancelled job only ran with older tokens | DB |
| I8 | `attempts ≤ max_attempts`; `failed` ⇒ all attempts used | DB |
| I9 | The transition audit log (written by a trigger) contains only legal edges with the right token change | DB |
| I10 | Sampled every second during the run: no running job has a lease further than `now() + 2 × lease` | DB |

The checker itself was tested: `InvariantsTest` corrupts a database by hand in each way above, and checks that exactly that invariant fires.

## Every run

All runs are listed here, including the failed and inconclusive ones. Evidence runs are at revision `151c030`.
Development runs (`runs/dev`) are at `fc96113` plus the uncommitted harness code.

| Log | What | Result |
|---|---|---|
| `runs/dev/20261110T141410Z-none-seed7.log` | first end-to-end run, 10 jobs | INCONCLUSIVE (exit 2): too small for faults to matter, as expected |
| `runs/dev/20261110T141442Z-none-seed1.log` | harness started with mode `none` on a `no-fencing` schema | aborted on SCHEMA MISMATCH before any job (exit 1), as intended |
| `runs/dev/20261110T141452Z-none-seed11.log` | 1,000 jobs | **INCONCLUSIVE: 0 fenced executions.** The pauses were hitting idle workers (see below) |
| `runs/dev/20261110T141657Z-none-seed11.log` | 1,000 jobs after the pause fix | INCONCLUSIVE: 4 zombies, all fenced; only 31 cancels hit running jobs (threshold 50) |
| `runs/dev/20261110T141827Z-load-seed1.log` | load mode check, 20 rps × 30 s | SHIP |
| [`runs/20261110T142159Z-none-seed1.log`](../runs/20261110T142159Z-none-seed1.log) | **10,000 jobs, seed 1** | **PASS** |
| [`runs/20261110T143320Z-none-seed2.log`](../runs/20261110T143320Z-none-seed2.log) | **10,000 jobs, seed 2** | **PASS** |
| [`runs/20261110T144446Z-none-seed3.log`](../runs/20261110T144446Z-none-seed3.log) | **10,000 jobs, seed 3** | **PASS** |
| [`runs/20261110T145612Z-naive-claim-seed4.log`](../runs/20261110T145612Z-naive-claim-seed4.log) | mutant `naive-claim`, 10,000 jobs | **caught**: I9 (357 jobs), I8 (1) |
| [`runs/20261110T150738Z-no-fencing-seed5.log`](../runs/20261110T150738Z-no-fencing-seed5.log) | mutant `no-fencing`, 10,000 jobs | **caught**: I2 (28), I3 (29), I5 (233), I7 (253), I9 (263) |
| [`runs/20261110T151905Z-load-seed1.log`](../runs/20261110T151905Z-load-seed1.log) | load, 100 rps, 20/60/15/5 mix, 5 min | SHIP against the pre-registered criteria |
| [`runs/20261110T152423Z-load-creates-seed1.log`](../runs/20261110T152423Z-load-creates-seed1.log) | load, 100 creates/s, 5 min | DON'T SHIP: the queue grows 75 jobs/s |

**Number of runs:** 12 harness runs. Of these, 7 are evidence runs (3 normal, 2 mutants, 2 load) and 5 are development runs.
Before the harness existed there were also manual smoke checks: local processes with `kill -9`, then the compose stack with
`docker kill` and `docker pause`. Their output was not saved to files; what they showed is recorded in `AI_USAGE.md`.
`worker-clock-lease` and the real clock-skew run (`libfaketime`) were not run as chaos runs. That mutant is covered by `MutantTest` and I10.

## The three PASS runs: did the faults have teeth?

| Counter | seed 1 | seed 2 | seed 3 |
|---|---|---|---|
| kills / of an executing worker | 314 / 300 | 317 / 303 | 313 / 291 |
| executions interrupted by a kill | 258 | 256 | 249 |
| pauses past the lease (all on executing workers) | 44 | 44 | 45 |
| **zombie executions fenced** | **36** | **34** | **39** |
| cancels that hit a running job | 290 | 241 | 250 |
| cancels that hit a queued job | 19 | 22 | 24 |
| cancels answered 409 (job finished first) | 589 | 607 | 594 |
| concurrent duplicates answered 200 | 2,312 | 2,271 | 2,363 |
| idempotent replays answered 200 | 4,862 | 4,786 | 4,904 |
| conflicting resends answered 409 | 83 | 87 | 109 |
| jobs run more than once | 691 | 676 | 660 |
| jobs failed / of them by lease loss only | 4 / 3 | 5 / 1 | 4 / 1 |
| timeouts / 5xx | 0 / 0 | 0 / 0 | 0 / 0 |
| I10 samples | 664 | 664 | 666 |
| run time | 670 s | 672 s | 673 s |

The cancel counts add up. Seed 1: 898 cancel requests = 309 cancelled jobs (19 queued + 290 running) + 589 answered 409.

**About the 1–3 failed jobs per run.** A death counts as an attempt (poison-job protection), so a job can be failed without
any error in its code. This happens when it loses 3 attempts in a row to an emulated failure (5%) or a kill (about 2–3%).
The design predicted about `0.07³ × 9,000 ≈ 3` such jobs per run, and we saw 1–3 failed by lease loss only. It is a known price, not a bug.

## Mutants: proof that the checks can see the bugs

- **`naive-claim`** produced **357 double claims**, and still I2 and I3 passed: no job had two effects. Fencing hid
  the bug: the first claimer was fenced 396 times. Only the audit log (I9: `running → running`) and one job with too
  many attempts (I8) showed it. A harness that counted only effects would have reported this broken claim as correct.
  The self-check review of the spec predicted this (`REVIEW.md`, B1), and that is why I9 exists.
- **`no-fencing`** produced 28 double effects (I2). 233 cancels were answered 200 `cancelled`, but the job later became
  `succeeded` (I5): the worker's commit overwrote the cancel. Its fenced-execution counter is 0, as it must be without fencing.

## How do we know 10,000 jobs was enough?

The number itself proves nothing. What we rely on:

1. **The dangerous paths were reached hundreds of times per run.** About 250 executions were cut by `kill -9`, 34–39 zombies came back with a stale token, 240–290 cancels hit a running job, and about 2,300 concurrent duplicate creates were made. Every one of these ended in a correct state.
2. **Seeded bugs are caught at this size, and with a margin:** 357 double claims and 28 double effects in one run. A bug that shows up in about 1 of 30 kills would still be seen several times.
3. **Three seeds** gave the same picture.
4. **What it cannot show:** a race that needs a window of about 1 ms to line up with a commit. The cancel/complete commit race is one, and here chaos timing is a weak tool. It is tested directly instead: `ConcurrencyTest.cancelAndCompleteRaceHasExactlyOneWinner` runs 500 truly concurrent pairs. More jobs would not help much. Widening the window would, for example with an injected delay between statements.

## What the harness does NOT catch

- **Postgres failure or failover.** Only workers are killed. The guarantee assumes a durable, single Postgres.
- **The API process being killed mid-request.** It is covered by idempotency in theory, but never injected.
- **Network faults:** partitions, slow or half-open TCP between worker and database, packet loss. Pauses are the closest thing we have.
- **A clock jump on the database server.** Every decision uses the database clock, so a jump there moves all leases at once.
- **External side effects.** The effect lives in the same database. A real S3 or GPU effect would be at-least-once.
- **Sustained high load during chaos.** The chaos runs are paced at 15 jobs/s. Capacity is measured separately, without faults.
- **Long-term effects:** table and index bloat after days of heartbeats, autovacuum, disk full, schema migrations during traffic.
- **Graceful shutdown on SIGTERM** (stretch, not tested), and the real process-clock skew run (prepared, not run).
- **Scheduler-level races** narrower than our timing granularity. See the point above about the 1 ms window.
