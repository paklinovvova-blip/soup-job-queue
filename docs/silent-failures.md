# Silent failures

Things that can break **without any error**: a green dashboard, a wrong result. For each one we list how it would
happen, what prevents it, and the exact test or run that checks it. "Chaos" refers to the invariants of the harness,
and the run logs are listed in [`chaos.md`](chaos.md).

## 1. Double claim

**How it would happen.** Two workers read the same queued job (`SELECT`), then both `UPDATE` it. Both run it.

**Protection.** Choosing and taking a job is one statement, `UPDATE ... WHERE id = (SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1)`.
The row lock is taken during selection, and locked rows are skipped.

**Why it is silent even with fencing.** The second claim bumps the token, so the first claimer's commit is fenced,
and there is **no double effect**. Only an extra attempt shows it. Checking "one effect per job" would therefore
*not* catch a double claim. The review of our own spec found this (`REVIEW.md`, B1), and that is why a database trigger logs
every transition and invariant I9 rejects `running → running`.

**Checked by:**
- `ConcurrencyTest.sixteenWorkersClaimEveryJobExactlyOnce`: 16 threads, 1000 jobs, 20 repetitions, zero duplicates and zero `running → running` rows.
- `ConcurrencyTest.claimSkipsRowLockedByAnotherTransaction`.
- `MutantTest.naiveClaimDoubleClaimsAndOnlyTheAuditLogShowsIt`: the broken claim gives about 1,400–1,600 double claims on 500 jobs, while the correct one gives 0.
- Chaos: I9 in every run, plus the `naive-claim` mutant run, which is expected to fail on I9.

## 2. Zombie worker writes a stale result

**How it would happen.** A worker freezes (GC pause, VM pause, `docker pause`) longer than its lease. The job is
reaped and finished by another worker. The frozen one wakes up and commits its old result: a second effect, or a cancelled job turned into a succeeded one.

**Protection.** A fencing token is bumped on every transition except success. Heartbeat, complete and fail all require
`status='running' AND lease_token=:mine`, so a zombie updates 0 rows and its transaction rolls back. As a second line of defence,
`effects.job_id` is a primary key.

**Checked by:**
- `ConcurrencyTest.zombieAfterReclaimIsFencedAndNewOwnerWins` and `staleTokenCannotHeartbeatCompleteOrFail`.
- `WorkerTest.frozenWorkersJobIsReapedAndRunsAgainExactlyOnce`: requeued after 4–7.5 s, 2 executions, 1 effect.
- `MutantTest.noFencingLetsAZombieWriteASecondEffect`: without fencing and without the PK, there are 2 effects.
- Chaos: `docker pause` past the lease, then I2, I3 and I7, plus the counter `executions fenced`. The `no-fencing` mutant run is expected to fail on I2 or I5.
- On the compose stack, a paused worker logged `gave up: fenced (commit fenced)` after `unpause`.

**Harness pitfall found on the way.** The first pause implementation paused workers that had *already finished*
their 100–500 ms job (the `docker pause` CLI lands 100–300 ms after the victim is chosen). The result was 0 zombies, and
the harness flagged the run INCONCLUSIVE. Now a pause that lands on an idle worker is undone and retried. See `AI_USAGE.md`.

## 3. Cancel and complete race

**How it would happen.** The cancel says "cancelled" while the worker commits the result at the same moment. The
client believes the job did not run, but the effect exists.

**Protection.** Both are conditional `UPDATE`s on the same row: cancel requires `status IN ('queued','running')` and bumps the token,
complete requires `status='running' AND lease_token=:mine`. The row lock orders them. The loser waits, re-checks its
`WHERE` against the new row version and changes nothing. The cancel response reports what actually happened: 200 `cancelled` or 409 `job_finished` with the job.

**Checked by:**
- `ConcurrencyTest.cancelAndCompleteRaceHasExactlyOneWinner`: 500 truly concurrent pairs, exactly one winner every time, no effect on any cancelled job, clean audit log.
  The split was 476 cancel / 24 complete: lopsided, but both branches were exercised.
- `WorkerTest.cancelDuringExecutionStopsTheWorkerWithinOneHeartbeat`: stopped in under 1.5 s, no effect.
- `JobApiTest.cancelRunningRevokesTheLease`.
- Chaos: I5 (every cancel response matches the final state), I3, and I7 (a cancelled job only ran with older tokens).
  Half of the cancels target running jobs, because in a deep backlog cancels only ever hit queued jobs. We saw that on our own smoke run.

## 4. Duplicate on request retry

**How it would happen.** The client's POST times out, it retries, and two training jobs are created.

**Protection.** `Idempotency-Key` is required. `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING`: the unique index decides the race.
The second request waits for the first to commit and then returns the existing job (200). The same key with a different payload returns 409,
and payloads are compared in canonical form: keys sorted, numbers by value.

**Checked by:**
- `JobApiTest.fiftyConcurrentDuplicatesCreateOneJob`: 50 parallel POSTs over real HTTP give one 201, 49 × 200 and one row.
- `JobApiTest.repeatWithSameBodyReturns200AndSameJob` and `sameKeyDifferentBodyIs409WithExistingJobId`.
- `CanonicalJsonTest`.
- Chaos: I1 (every accepted key has exactly one job) and I6 (every response for a key names the same job), with about 30% of keys duplicated, concurrently or as delayed retries, and about 1% resent with a different body.

## 5. Transaction isolation level

**How it would happen.** Logic of the form "read, decide in Java, write" under READ COMMITTED: two transactions read the
same state and both act on it. This is lost update and write skew.

**Protection.** There is no read-then-write. Every transition is a single conditional `UPDATE`. In READ COMMITTED,
Postgres waits for a concurrent writer of the row and then **re-evaluates the WHERE clause on the newest version**, which makes each
statement an atomic compare-and-set. SERIALIZABLE would also be correct, but it adds `40001` retries for no gain.

**Checked by:**
- Every test and chaos run uses READ COMMITTED, the Postgres default, unchanged.
- The `naive-claim` mutant is exactly the read-then-write pattern, and it breaks under READ COMMITTED: `MutantTest` and the chaos mutant run.
- `ConcurrencyTest.reaperSkipsLockedRowAndStalledTransactionIsAborted`: a transaction left open and idle (a paused worker) does not block others, because the reaper uses `SKIP LOCKED`, and Postgres aborts it after 2 s (`idle_in_transaction_session_timeout`).

## 6. Clock skew

**How it would happen.** Worker A's clock is 10 minutes behind. It computes "my lease is valid until 12:05" while the
others think it is 12:10. Leases look expired too early or too late: jobs are stolen, or a dead worker's job is never reaped.

**Protection.** All time decisions use the database clock: lease expiry, reaping, max runtime, retry backoff.
Worker wall clocks are never compared with anything. The only local timing is `System.nanoTime` (monotonic), used for self-fencing and the watchdog.

**Checked by:**
- `NoWallClockTest`: scans the service code and fails if lease, reaper, heartbeat or backoff code reads `Instant.now`, `currentTimeMillis` or similar. It also checks that the scan would catch such a call.
- `MutantTest.workerClockLeaseWritesALeaseAnHourAhead`, plus chaos invariant I10, sampled every second.
- Every worker logs its wall clock next to `SELECT now()` at startup.
- A real process-clock skew run with `libfaketime +1h` is prepared in the image but not run (stretch).

**Not covered.** A clock jump on the **database** server itself, for example an NTP step. All leases would shift together.

## 7. More silent failures found while building it

| Failure | Protection | Checked by |
|---|---|---|
| A Java scheduled task that throws once is **cancelled forever**, silently: the reaper dies, and killed workers' jobs stay `running` | Every periodic body is wrapped in `try/catch (Throwable)` | `WorkerTest.reaperKeepsRunningAfterATickThrows` |
| The database is unreachable, but the worker keeps working on a job it may have lost | Self-fencing after `lease − heartbeat` without a confirmed heartbeat (monotonic clock) | `WorkerTest.workerSelfFencesWhenHeartbeatsKeepFailing`: stopped in 1.5–2.4 s, before the lease could expire |
| A hung execution while the heartbeat thread keeps the lease alive forever | Max runtime in heartbeat and reaper, plus a watchdog that exits the process | `WorkerTest.hungExecutionIsReapedAndTheWorkerExits`, `JobRepositoryTest.reapEnforcesMaxRuntimeEvenWithLiveHeartbeat` |
| Poison job: it crashes every worker that takes it | A death counts as an attempt, so after 3 it is `failed` | `WorkerTest.crashingJobEndsFailedAfterThreeClaims` |
| A paused worker holds a row lock inside an open transaction, and the whole reaper stalls | Reaper with `SKIP LOCKED`; Postgres aborts idle transactions after 2 s | `ConcurrencyTest.reaperSkipsLockedRowAndStalledTransactionIsAborted` |
| A "green" chaos run that never reached the dangerous code | Counters (kills during execution, fenced zombies, cancels of running jobs, concurrent duplicates); a run without them is INCONCLUSIVE (exit 2) | Dev runs in `runs/dev` that were correctly flagged |
| Docker does not restart a container after `docker kill`, so the fleet shrinks during a chaos run | The harness runs `docker start` itself | Checked on the stack: `exited`, `RestartCount=0` five seconds after `docker kill` |
