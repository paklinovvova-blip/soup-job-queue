# Review: add-job-queue-service

Self-check review of `proposal.md`, `design.md`, `tasks.md` and the three spec deltas, done before implementation starts.
No spec, design or task file was changed. All findings are below.

## How this review was done

Skills available in this session and how each was used:

| Skill | Available | Used | How |
|---|---|---|---|
| `system-design-skills:task-scheduling` | yes | yes | Lease and visibility timeout, heartbeat, poison task, re-run storm checklist (claim, lease, reaper, retries). |
| `system-design-skills:resilience-failure` | yes | yes | Silent failure and amplifier checklist: timeouts, retries, hung dependencies, failure surfacing. |
| `system-design-skills:consistency-coordination` | yes | yes | Fencing tokens, single clock, split-brain or zombie writer, what the reader is guaranteed to see. |
| `system-design-skills:data-storage` | yes | yes | Schema, indexes, access paths, connection budget, lock contention. |
| `deep-review:deep-review` | yes | partly | Used its severity rubric, finding format and "challenge every finding" rule. Skipped the calibration interview, because this session cannot ask the author questions. The specialist passes ran inline, not as separate subagents, so this falls below the skill's own depth floor. |
| `arxitect:architecture-review` | yes | no | It reviews code (SOLID, component coupling, API surface), and no code exists yet. Run it after task groups 2–4 land. |
| spec-driven-dev (plan review) | agents only (`sdd-plan-reviewer`, `sdd-plan-diligence`) | no | No plan-review skill could be invoked, and these agents fix the plan in place, which this review must not do. |

Each finding below was checked against the exact SQL in `design.md` by walking concrete interleavings. The rate
estimates are back-of-the-envelope figures from the stated defaults (8 workers, 300 ms mean job, kill every 2 s,
pause every 15 s), not measurements.

## Overall assessment

The core concurrency design is sound. It uses an atomic claim with `SKIP LOCKED`, a status plus fencing-token guard
on every worker write, a cancel that bumps the token, an effect insert and success transition in one transaction with
the row lock taken first, a DB-only clock, and READ COMMITTED with statement-level compare-and-set. I found no way to
get a double effect or a wrong final state in the `none` mode.

The weak part is the **evidence layer**. Two of the three mutants are masked by fencing, so the harness would not catch
them for the reason the spec states. The workload as specified would also run most of the drain phase with no faults.
These are blockers because the "correctness under failure" and "silent failures" grades rest on that evidence, not on
the code alone. Confidence: high for B1 and B2, medium-high for B3.

---

## Blockers

### B1. `naive-claim` is masked by fencing; the spec expects invariants that will not fire

- **Location:** `specs/chaos-verification/spec.md` → "Seeded-bug modes", scenario "Harness catches the naive claim"; `design.md` D15; `tasks.md` 8.2.
- **Problem:** The mutant does `SELECT id … LIMIT 1` and then `UPDATE … WHERE id=:id`, and it keeps `lease_token=lease_token+1`. Completion and heartbeat stay fenced, because the mutant only touches the claim. A double claim therefore leaves the first claimer holding a stale token.
- **Failure scenario:** W1 and W2 both select job X. W1's UPDATE commits with token t. W2's UPDATE waits for the row lock, re-evaluates `id=:id` (still true) and commits with token t+1. W1 runs for 100–500 ms, and its completion `WHERE lease_token=t` updates 0 rows, so W1 records `fenced`. W2 completes and writes one effect with token t+1. I2, I3 and I7 all pass. The only visible trace is `attempts` being bumped twice. I8 fires only if a double claim happens to land on a job's 3rd claim. My estimate is a few such jobs per 10k-job run, so detection is probabilistic, and it happens through I8 rather than the invariants the spec names. A cancel or a success resurrected by the naive UPDATE (I3/I5) needs the SELECT→UPDATE gap (≈1 ms) to span a whole job run (≥100 ms), which practically never happens without an injected delay. Outcome: either "expected failure: NOT caught", or "caught" by I8 for an indirect reason. Both are awkward to defend in the live discussion.
- **Suggested fix:** Add an invariant that checks the state machine directly, and change the scenario to name it:
  - **I9 – only legal transitions.** Add a `job_transitions` table filled by an `AFTER UPDATE` trigger on `jobs`. Log a row when the status changes, when `lease_token` changes, or when `OLD.status` is terminal. Heartbeats change neither, so they are not logged. The allowed edges are `queued→running (+1)`, `running→queued (+1 reap | +0 retry)`, `running→failed (+1 reap | +0 fail)`, `running→succeeded (+0)`, and `queued|running→cancelled (+1)`. Any other row is a violation, for example `running→running (+1)` (a double claim) or anything out of a terminal state. The trigger runs in every mode, so a mutant cannot bypass it. The cost is about 25 lines of SQL and one harness query.
  - This also gives the requirement "Terminal states are final" (`job-execution`) a direct check, which it has none of today.
  - Cheaper fallback with no trigger: assert `attempts = number of claims`. That needs a claim counter written by the claim statement itself, so the trigger is cleaner.
  - In `docs/silent-failures.md`, state honestly that fencing is the second line of defense against a double claim, and that the double claim itself is caught by I9 and by task 2.3/5.2.
- **Confidence:** High on the interleaving. Medium on the exact I8 hit rate.

### B2. Faults stop when submissions end, so most of the drain runs fault-free and cancels rarely hit running jobs

- **Location:** `specs/chaos-verification/spec.md` → "Chaos workload" and "Quiescence and timeout"; `design.md` D14; `tasks.md` 7.2/7.3.
- **Problem:** The workload does not fix a submission rate ("from many concurrent clients"), and faults stop "after all submissions and cancellations are sent". Drain capacity is about 27 jobs/s, or about 18 jobs/s with kills, since a restarted Spring Boot JVM is out for several seconds. If 10k creates are sent in 1–2 minutes, the remaining 6–8 minutes of execution run with no kills and no pauses.
- **Failure scenario:** Submissions finish at t=90 s, so only about 45 kills and about 6 pauses happen. Thousands of executions then run with no faults at all. And because the backlog is 10k deep while cancels go out, nearly all of the ~1,000 cancels hit `queued` jobs. That leaves the "cancel of a running job" path, which is the author's chosen design fork D8, almost untested. The run is still green and not flagged inconclusive, because some kills did interrupt executions and some executions were fenced.
- **Suggested fix:** Pick one and write it into the spec:
  1. Pace creates below the drain rate, for example 15–20 jobs/s, so the queue stays shallow and faults run for the whole ~10 minutes.
  2. Keep injecting faults until ≥95% of jobs are terminal, then stop, unpause and start every worker, and wait for quiescence.
  For cancels, make about half of them targeted: poll `GET` until the job is `running`, then cancel after a random 0–400 ms. Add "cancels that hit a running job < 50" to the toothless check. Count them from `executions.outcome='cancelled'`, which is the only reliable source, because the cancel response does not include the previous status.
- **Confidence:** High.

### B3. `worker-clock-lease` is either masked by fencing or caught only by the 15-minute timeout

- **Location:** `design.md` D15; `specs/chaos-verification/spec.md` → "Seeded-bug modes" ("A chaos run in each mutant mode SHALL be expected to fail"); `tasks.md` 8.2.
- **Problem:** The sign of the skew decides what happens, and no invariant is named for this mutant.
  - **Clock behind** (expiry written in the past): the reaper reaps live jobs within ≤1 s, a new worker claims them, and the original worker is fenced. There is never a double effect. Jobs just burn attempts and some end `failed` with `lease expired`. I8 ("failed jobs used all attempts") still passes, so the run comes out **NOT caught**. The real damage is innocent jobs failing, which is a silent failure no invariant checks.
  - **Clock ahead** (+1 h): leases never expire, and a killed worker's job sits in `running` until the 15-minute quiescence timeout. I4 catches it, but only after 15 minutes. It also only works if the skew is larger than the timeout.
- **Failure scenario:** The author runs `WORKER_CLOCK_SKEW_MS=-3600000`. Every invariant passes, the output says "expected failure: NOT caught", and the exit code is non-zero. Task 8.2 then records a mutant the harness cannot catch.
- **Suggested fix:** Fix the sign to **ahead**. Add a cheap sampled invariant **I10 – lease horizon**: once per second the harness runs `SELECT count(*) FROM jobs WHERE status='running' AND lease_expires_at > now() + 2*lease`, and any non-zero value is a violation. That catches the mutant in seconds. For the behind direction, report "jobs failed only by reaping while their worker was alive", meaning executions that ended `fenced` where the worker was neither killed nor paused, as a counter with a threshold. Alternatively, drop this mutant (see S13) and keep only the skew run, once S5 makes that run meaningful.
- **Confidence:** Medium-high.

---

## Should-fix

### S1. The multi-row reaper can block on a paused worker's row lock and can deadlock with other reapers

- **Location:** `design.md` D6 (reaper statement and "concurrent reapers are safe: row locks serialize them").
- **Problem:** The reaper is a single `UPDATE … WHERE status='running' AND …` over many rows, run by 8 processes every second. (a) A worker frozen by `docker pause` *inside* its completion transaction holds the row lock for lease + 3 s = 8 s, and every reaper in every worker blocks on that row for 8 s. So does any cancel on that job. (b) Two reapers that lock the same rows in different orders get a `40P01` deadlock. The scan order is usually the same, but it is not guaranteed while heartbeats move rows in `jobs_running_idx`. The design does not say how `40P01` is handled.
- **Failure scenario:** A pause lands in the ~5 ms completion window. Reaping stalls cluster-wide for 8 s, so a job killed in that window is requeued after ~14 s instead of ~6 s. The harness's cancel request on that job blocks for 8 s and times out on the client side, so I5 cannot judge it. Separately, an unhandled `40P01` thrown from a scheduled task kills that reaper forever (see S2).
- **Suggested fix:**
  ```sql
  WITH expired AS (
    SELECT id FROM jobs
     WHERE status='running' AND (lease_expires_at < now() OR claimed_at < now() - :maxRuntime)
     ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 100)
  UPDATE jobs j SET … FROM expired e WHERE j.id = e.id;
  ```
  Also set `idle_in_transaction_session_timeout` to about 2 s for worker connections, so a paused transaction gets aborted. Set the harness HTTP timeout to ≥15 s, and log a timed-out response as `unknown`, never as a pass or a fail.
- **Confidence:** High for (a), medium for (b).

### S2. Silent death of the heartbeat or reaper timer; DB exceptions are undefined

- **Location:** `design.md` D6 and D16; `specs/job-execution/spec.md` → "Heartbeat extends the lease…".
- **Problem:** The design defines "0 rows updated" but not "the statement threw": connection timeout, `40P01`, pool exhausted, Postgres restart. In Java, a task scheduled with `ScheduledExecutorService.scheduleAtFixedRate` that throws once is **silently cancelled for good**. That is the classic silent failure the 20% grade looks for.
- **Failure scenario:** A brief DB hiccup makes each worker's reaper throw once. All 8 reaper timers are now dead and nothing logs it. From then on, every kill leaves a job in `running` for good, and the run times out 15 minutes later with no hint why. If the heartbeat dies the same way, live jobs get reaped, work is wasted, and the only symptom is a higher fenced count.
- **Suggested fix:** Wrap every periodic body in `try { … } catch (Throwable t) { log.error(…) }`, or use Spring `@Scheduled`, which logs and keeps going. Add this rule to the spec: "a heartbeat that fails with an exception is retried on the next tick; if no heartbeat has succeeded for `lease − 1 s` by the local monotonic clock (`System.nanoTime`), the worker stops the execution." That self-fencing does not depend on wall-clock skew. Add one test that makes the repository throw once and checks the reaper still runs afterwards.
- **Confidence:** High.

### S3. `Thread.interrupt` can leak into the next job; the heartbeat must be bound to one attempt

- **Location:** `design.md` D6 ("sets a flag and interrupts the execution thread"), D12.
- **Problem:** An interrupt sent at the end of attempt A can arrive after the execution thread has finished A and claimed B. With one long-lived execution thread, the stale interrupt then aborts B's Hikari `getConnection()` or a sleep in B's work slices. The same thing happens if the heartbeat reads a mutable "current job" field and checks B with A's token.
- **Failure scenario:** Job B is spuriously aborted, recorded as `fenced` or `failed`, and burns an attempt. Correctness holds because the DB guards everything, but the noise feeds the B3/innocent-failure counters, and a reviewer will ask why B was fenced.
- **Suggested fix:** Give each attempt an immutable `Attempt(jobId, token, AtomicBoolean stop)`. Start one heartbeat task per attempt and cancel it before the next claim. Have work slices check `attempt.stop` instead of using `Thread.interrupt`. If the interrupt is kept, call `Thread.interrupted()` to clear it before every claim.
- **Confidence:** Medium-high (an implementation pitfall the design leaves open).

### S4. A hung execution leaves its worker stuck forever, and the max-runtime scenario has no test

- **Location:** `design.md` D6; `specs/job-execution/spec.md` → "Maximum runtime"; `tasks.md` 4.5.
- **Problem:** The `claimed_at` guard frees the *job* after 60 s. But the execution checks its stop flag only between 50 ms slices, and a truly hung execution never reaches that check. With one job per process (D12), that worker never claims again while health still reports UP. Fleet capacity drops silently. Task 4.5 also tests only the "frozen thread" case, which is the lease path, and the "poison" case. The spec scenario "Hung execution with a live heartbeat" has no task.
- **Failure scenario:** One hang per day leaves 7 workers effectively alive after a day, with nothing showing it.
- **Suggested fix:** If the stop flag is not honored within, say, 5 s, the worker logs an error and calls `System.exit(1)`, and the restart policy brings it back. Add a test with a hang hook (for example payload `{"simulate":"hang"}`) that checks the job is reaped after `maxRuntime` with a new token and the worker process exits. A short `maxRuntime` (2 s) via env keeps the test fast. Optionally, make the health endpoint report DOWN when the current attempt is older than `maxRuntime`.
- **Confidence:** High.

### S5. The clock-skew run can pass for the wrong reason, and how skew gets injected is unspecified

- **Location:** `specs/chaos-verification/spec.md` → "Clock-skew run"; `tasks.md` 4.7, 8.3; `design.md` D14 (no mention).
- **Problem:** Containers share the host kernel clock, so "some workers have a local clock offset of ±1 h" needs a mechanism, and none is given. If the offset is applied only through an injected `Clock` bean, the run proves only that the code does not read that bean. Code that calls `Instant.now()` or `System.currentTimeMillis()` directly would still pass. The run would be green by construction.
- **Failure scenario:** A later change computes backoff as `Instant.now().plus(…)` and binds it as a parameter. Both the skew run and test 4.7 stay green, because the real JVM clock was never skewed.
- **Suggested fix:** Skew the process clock for real in the skew run. Use `libfaketime` in the worker image (`LD_PRELOAD=/usr/lib/x86_64-linux-gnu/faketime/libfaketime.so.1`, `FAKETIME="+1h"`, `FAKETIME_DONT_FAKE_MONOTONIC=1`; works on glibc-based Temurin images, not Alpine). Then check in the run log that the worker's `Instant.now()` really is off, for example by logging it at startup next to `SELECT now()`. A cheap complement is a test that greps the lease, reaper and backoff code for `Instant.now`, `System.currentTimeMillis` and `LocalDateTime.now`. Write the mechanism into D14.
- **Confidence:** Medium-high (verify libfaketime with the chosen base image).

### S6. Mutant setting: "worker setting only" contradicts the API-side Flyway migration

- **Location:** `specs/chaos-verification/spec.md` → "Seeded-bug modes" ("enabled only by an explicit worker setting"); `design.md` D3 (Flyway runs only in `api`) and D15 (`no-fencing` applies `db/mutant/no-fencing/V100__drop_effect_pk.sql`); `tasks.md` 6.2.
- **Problem:** Dropping the effect PK happens in the API container, so the API must read `QUEUE_MUTANT` too. Read literally, the spec forbids that. If only workers get it, the PK stays, and `no-fencing` produces unique-violation errors instead of duplicate effects.
- **Failure scenario:** `QUEUE_MUTANT=no-fencing` is set only on `worker`. The PK still exists, so the second effect insert rolls back, and the job ends `succeeded` with one effect. I2 passes. Whether the mutant gets caught then depends on I5 (cancel overwritten) alone, which is a weaker and slower catch.
- **Suggested fix:** Change the spec to "an explicit setting on the API and worker containers". Have the harness pass it to both and assert at startup that the printed PK status matches the mode (it already prints it; make a mismatch fatal).
- **Confidence:** High.

### S7. The error body format conflicts with the 409 cancel response that "carries the job"; I5 depends on it

- **Location:** `specs/job-api/spec.md` → "Error format" (body is exactly `{"error","message"}`) vs "Cancel is idempotent…" (409 "with … the current job") and "Cancel response matches the final state" (a 409 implies the job ends in "the state the response reported").
- **Problem:** The 409 body shape is undefined, and I5 needs the reported state. The same gap exists for `idempotency_conflict`: does it return the existing job id? I6 ("all responses for one key carry the same job id") needs to know.
- **Failure scenario:** The implementation follows the "Error format" requirement, so the 409 has no state, and the harness cannot evaluate I5 for the race cases that matter most.
- **Suggested fix:** Specify `{"error": "...", "message": "...", "job": {…}}` for `job_finished`, and decide whether `idempotency_conflict` includes `jobId`. Write I6 to cover whichever choice is made.
- **Confidence:** High.

### S8. The 100 RPS verdict has no defined request mix, no pass criteria, and throughput is capped at about 27 jobs/s

- **Location:** `specs/chaos-verification/spec.md` → "Load run for the verdict"; `design.md` D12; `tasks.md` 7.6, 9.4.
- **Problem:** "Mixed request stream (creates, status reads, list pages, cancels)" gives no ratios and no SHIP threshold. Execution capacity is `8 / 0.3 s ≈ 27 jobs/s`, or about 20 jobs/s if the worker waits 100 ms after each job (see N4). The verdict is decided by the mix: 20% creates (20 jobs/s) is SHIP-able, while 50% creates (50 jobs/s) makes the queue grow by about 25 jobs/s with no bound. If "100 RPS" in the brief means 100 *job submissions* per second, then 8 single-job workers are a DON'T SHIP by arithmetic, before any run.
- **Failure scenario:** The mix is picked after seeing the numbers, so the verdict looks post-hoc in the discussion.
- **Suggested fix:** Pre-register the mix and the criteria in the spec before the run. For example: mix 20% create / 60% get / 15% list / 5% cancel; SHIP if API p99 < 200 ms, 5xx < 0.1%, and queue depth bounded (slope ≈ 0 over the last 3 minutes). Also run or state the "100 creates/s" interpretation. Little's law gives `100 × 0.3 s = 30` concurrent executions, so about 40 worker slots with headroom, or threads per worker. Put that math in `docs/verdict.md`. It also turns D12 (one job per process) into an explicit, quantified trade-off.
- **Confidence:** High.

### S9. A poison job "crashes its worker", but nothing can make a worker crash

- **Location:** `specs/job-execution/spec.md` → "Dead workers are detected…", scenario "Poison job"; `tasks.md` 4.5.
- **Problem:** The emulated execution only fails with a caught exception (D13). Nothing kills the worker deterministically for one specific job, so the scenario cannot be tested as written.
- **Suggested fix:** Add a test-only payload directive, for example `{"simulate":"crash"}` calling `Runtime.getRuntime().halt(137)` in the worker, and `{"simulate":"hang"}` for S4. Or test at the repository level by claiming three times and expiring the lease by hand. State which one in the task.
- **Confidence:** High.

### S10. Kill attribution and victim choice are underspecified, so the teeth counters are noisy

- **Location:** `design.md` D14 ("`executions` rows with `outcome IS NULL` and the killed worker's id"); `specs/chaos-verification/spec.md` → "Worker crash injection", "Zombie injection".
- **Problem:** The harness knows container ids, but worker ids are fresh per process start, and no mapping is defined. Victims are "random", but a Spring Boot worker takes several seconds to start, so with a kill every 2 s many kills hit a worker that is still booting or idle. The same goes for pauses: pausing an idle worker creates no zombie.
- **Suggested fix:** Make the worker id `<HOSTNAME>-<uuid>` (in Docker, `HOSTNAME` is the container id) and have the worker log it. Choose kill and pause victims among containers whose worker currently owns a `running` job (a single `SELECT lease_owner …`), with a random fallback. Log "victim was executing: yes/no" in the fault log.
- **Confidence:** Medium-high.

### S11. Canonical JSON hashing: `ORDER_MAP_ENTRIES_BY_KEYS` probably does not sort `JsonNode` trees

- **Location:** `design.md` D9.
- **Problem:** As far as I know, `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS` applies when serializing `java.util.Map`. An `ObjectNode` keeps insertion order unless a JsonNode-specific sort feature is enabled (newer Jackson 2.x has one). The payload will very likely be read as `JsonNode` to pass it through to `jsonb`. Numbers are also not canonical: `1`, `1.0` and `1e0` hash differently.
- **Failure scenario:** The scenario "same payload, different key order → 200" fails, and someone loses an hour on it. That is caught by the existing test, which is why this is should-fix rather than a blocker.
- **Suggested fix:** Canonicalize with a small recursive function that sorts `ObjectNode` fields into a `TreeMap`. Or deserialize into `Map<String,Object>` with `ORDER_MAP_ENTRIES_BY_KEYS`. Document the number rule, or normalize with `JsonNodeFeature`/`BigDecimal` handling. Verify against the Jackson version Spring Boot brings in.
- **Confidence:** Medium. Check the exact feature name and version before relying on it.

### S12. Gaps between spec scenarios and the tasks that test them

- **Location:** `tasks.md` vs `specs/job-execution/spec.md` and `specs/job-api/spec.md`.
- **Problem / fix:** These scenarios have no task that checks them. Add one line to the matching task for each:
  - "Hung execution with a live heartbeat" → 4.5 (see S4).
  - "Locked rows are skipped": hold `FOR UPDATE` on the oldest job in one transaction, and a claim from another must return the next job without waiting → 2.3.
  - "Backoff delays the retry" → 4.4.
  - "Counting runs after a crash" (2 execution rows, 1 effect) → 4.5.
  - "Restarted container gets a new worker id" → 4.1.
  - "Cancel before start: no execution of that job ever starts". No invariant checks this. A cheap one: for every `cancelled` job, all `executions.lease_token` are below `jobs.lease_token`, because cancel bumps the token last. I9 (B1) covers it too.
- **Confidence:** High.

### S13. Scope is large for a 4–6 hour budget; set a cut line now

- **Location:** `tasks.md` overall (about 45 tasks), `design.md` Context ("4–6 hours").
- **Problem:** The plan has three mutants, a skew chaos run, a load mode with a queue-depth time series, graceful shutdown, and five documents. On top of that, the evidence runs alone take about 1.5 h of wall-clock time: 3 seeds × ~10 min, 3 mutants, a skew run and a load run. The likely failure mode is running out of time on the harness, which carries the most grade weight.
- **Suggested fix:** Mark the tasks that come first: claim, fencing, cancel, reaper, I1–I9, kill + pause, `no-fencing` and `naive-claim` with I9, one load run, and the documents. Mark as stretch: `worker-clock-lease` (or replace it with I10 plus a real skew run), graceful shutdown (4.6), and the status-filter index. Replace the "one strategy class per affected repository method" (D15) with SQL variants chosen once at startup. Only `naive-claim` changes control flow, so it alone deserves a strategy. That is less code and easier to explain line by line.
- **Confidence:** Medium (a judgment call).

---

## Nice-to-have

- **N1. Wording: "write nothing further".** `job-execution` "Heartbeat…" says the worker "SHALL write nothing further for that job", but its own scenario and D7 record the `fenced`/`cancelled` outcome. Reword to "SHALL NOT change the job or write an effect".
- **N2. The token is not bumped on fail→queued.** D4 says the token is "bumped on every ownership change", but the D7 failure path releases ownership without a bump. That is harmless, because the next claim bumps it and `status='running'` guards the gap. Either bump it or reword D4, so the I9 edge table matches.
- **N3. Flaky timing in 4.5.** "Requeued within 6 s" equals lease + reaper with no slack: the last heartbeat comes up to 1 s before the freeze, plus reaper phase, plus statement time. Assert ≤ 7.5 s, and assert the lower bound (not before 4 s) instead.
- **N4. Unclear polling rule.** D5's "100 ms after a successful claim" reads as a 100 ms pause after every job, which costs about 25% throughput (S8). Say "claim again immediately after finishing a job; back off 100 ms → 1 s only when the claim returns nothing".
- **N5. Weak teeth check.** Also flag a run as inconclusive when there are zero concurrent duplicate POSTs that got 200, or too few cancels hit running jobs (B2). Define whether "inconclusive" exits 0. Before quiescence, the harness must unpause and start every worker.
- **N6. Connection budget and JVM sizing.** Each worker needs ≥3 connections (execution, heartbeat, reaper). The Hikari default of 10 × 8 workers + API + harness gets close to Postgres's `max_connections=100`. Set a pool of 4 per worker. On Docker Desktop (macOS), cap the JVMs (`-Xmx256m`, `-XX:TieredStopAtLevel=1` for faster restarts), so CPU contention after each kill does not delay heartbeats and cause spurious reaps.
- **N7. Shutdown grace.** Docker's default stop timeout (10 s) equals the 10 s grace, so SIGKILL lands exactly at the boundary. Set `stop_grace_period: 15s`.
- **N8. Stale `lastError`.** A job reaped once and then succeeded keeps `last_error='lease expired…'`. Clear it on success, or document that it means the last failed attempt.
- **N9. Extra invariant and index.** Each effect's `(job_id, lease_token)` matches an `executions` row with `outcome='succeeded'`, which catches an effect written outside a recorded run. Add an index on `executions(job_id)` for the harness queries.
- **N10. Define "accepted" in I1.** A key whose requests all timed out may or may not have a job, and both are fine. Spell this out so client timeouts do not show up as false I1 violations.
- **N11. Execution-record insert failure.** If the `executions` insert after the claim fails, the worker must not execute and should let the lease expire. Say so in D5.
- **N12. Reaper only in workers.** If all workers are down, nothing reaps. That is fine for the take-home, but it is worth one line in the design note: the reaper could also run in the API role.
- **N13. Untested crash points.** The API and Postgres are never killed. List this in "what the harness does NOT catch" (9.3), together with the ~1 ms cancel/complete commit race, which task 2.5 covers better than chaos does.

---

## What is fine, and why

- **Claim statement (D5).** `WHERE id = (SELECT … FOR UPDATE SKIP LOCKED LIMIT 1)` is a scalar subquery, so it runs once (as an InitPlan) and locks one row. If the row changed after the snapshot, the lock re-checks `status='queued'` and skips it. Keep `=`: switching to `IN (…)` can let the planner run the subquery more than once and lock more rows than the limit. The `jobs_queued_idx (run_after, created_at) WHERE status='queued'` index matches the `ORDER BY` exactly.
- **Fencing plus status guard on every worker write (D6, D7, D8).** Claim, reap and cancel all bump `lease_token`, and heartbeat, complete and fail all require `status='running' AND lease_token=:token`. A zombie, a reaped worker or a cancelled worker cannot write. Terminal states are final because every transition's `WHERE` lists the allowed source states.
- **Completion transaction order (D7).** `UPDATE jobs` first takes the row lock, so a concurrent cancel or reap waits and then re-evaluates against `succeeded`, which gives the 409 path. If the cancel commits first, the completion updates 0 rows and rolls back with no effect. Combined with `effects.job_id` as PK, this gives exactly-once effect with defense in depth, and I7 (effect token = final token) holds because nothing bumps the token after success.
- **READ COMMITTED (D10).** The reasoning is right: single-row conditional UPDATEs re-check their `WHERE` against the newest row version after waiting. SERIALIZABLE would only add `40001` retries.
- **DB `now()` only.** `now()` is the transaction start time. In autocommit statements that is the statement start, and if a statement waited on a lock, the expiry it computes errs toward a shorter lease. That is the safe direction.
- **Max runtime in both heartbeat and reaper (D6).** It closes the "live heartbeat, dead execution" hole for the job. S4 is only about freeing the worker.
- **Idempotent create (D9).** `ON CONFLICT DO NOTHING RETURNING *` plus a follow-up `SELECT` is correct in READ COMMITTED: the loser waits for the winner's commit, and the new statement sees the row. Test 3.2 (50 parallel POSTs) is the right check.
- **Keyset pagination (D11).** It is ordered on immutable `(created_at, id)`, so a walk never repeats or skips pre-existing rows. A row inserted late with an earlier `created_at` can be missed, which the spec explicitly allows.
- **`no-fencing` mutant.** Unlike the other two, it is caught robustly once S6 is fixed. A paused worker resumes after its job was reaped and finished by someone else, completes with no guard, and writes a second effect (I2). Or it overwrites a `cancelled` job (I5).
- **Evidence hygiene.** A fresh stack per run, the PK printout, a no-overwrite raw log, keeping failed runs, an explicit "timing is not reproducible" line, and exit codes tied to expectations all give honest, defensible evidence.
- **Attempts counted at claim.** This protects against poison jobs at a known price. Expected cost with defaults: per-attempt loss ≈ 5% random failure + ~1.5–2% kills ≈ 7%, so about `0.07³ × 9,000 ≈ 3` innocent jobs end `failed` per run. A clean run should show a handful of `failed` jobs, and I8 covers them. Put that number in `docs/chaos.md` so nobody reads it as a bug.
- **Rejected alternatives.** SQLite, a broker, JPA, advisory locks, a workers table and soft cancel are each rejected with a concrete reason. They make good material for the live discussion.
