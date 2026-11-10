# Spec Delta

## Purpose

Defines how workers claim, lease, execute, retry and complete jobs, how dead and zombie workers are
handled, and which delivery guarantee the queue gives: at-least-once execution, exactly-once effect.

## ADDED Requirements

### Requirement: Delivery guarantee
The system SHALL guarantee at-least-once execution and exactly-once effect: a job that is not cancelled
SHALL eventually reach `succeeded` or `failed`, a job MAY be executed more than once, and every job SHALL
have at most one effect, present if and only if the job is `succeeded`.

#### Scenario: Worker dies mid-execution
- **WHEN** a worker is killed with SIGKILL while executing a job
- **THEN** the job is executed again by another worker and ends `succeeded` with exactly one effect, or `failed` with none

### Requirement: Fencing token rule
Every job SHALL carry a fencing token that increases on every state transition except `running → succeeded`.
So claim, reap, retry release, failure, and cancel each produce a new token. The token of the attempt that succeeded
stays the job's final token.

#### Scenario: Token after a retry
- **WHEN** a job is claimed, fails its attempt, returns to `queued` and is claimed again
- **THEN** its token increased on each of those three transitions

#### Scenario: Token after success
- **WHEN** the current owner completes a job
- **THEN** the job's final token equals the token of that owner's attempt

### Requirement: Exclusive claim
A `queued` job whose earliest run time has passed SHALL be claimed by at most one worker at a time.
A successful claim SHALL atomically set the job to `running`, increment its attempt count and its fencing token,
record the owner, and set the lease expiry to database time plus the lease duration.

#### Scenario: Two workers race for one job
- **WHEN** two workers try to claim while exactly one job is eligible
- **THEN** one worker gets the job and the other gets nothing, and the job has one owner

#### Scenario: Locked rows are skipped
- **WHEN** another transaction holds a row lock on the oldest eligible job and a worker tries to claim
- **THEN** the worker claims the next eligible job without waiting for that lock

### Requirement: Claim order, eligibility and polling
Workers SHALL claim the oldest eligible job first, on a best-effort basis. A job SHALL NOT be claimed before its `run_after`,
which a retry sets with backoff. A worker SHALL try to claim again right after finishing a job,
and SHALL back off from 100 ms up to 1 s only while claims return nothing.

#### Scenario: Backoff delays the retry
- **WHEN** a job failed its first attempt and its `run_after` is 2 seconds in the future
- **THEN** no worker claims it during those 2 seconds

### Requirement: Every real run is recorded
Each claim SHALL be followed by an execution record with the job id, the fencing token, the worker id, the start time and,
when known, the outcome (`succeeded`, `failed`, `fenced`, `cancelled`, or empty if the worker died).
If writing this record fails, the worker SHALL NOT execute the job and SHALL leave it to lease expiry.

#### Scenario: Counting runs after a crash
- **WHEN** a job was executed by a worker that died and then by a second worker that succeeded
- **THEN** the job has two execution records and one effect

### Requirement: Heartbeat extends the lease only for the current owner
While executing, a worker SHALL renew its lease every heartbeat interval. A renewal SHALL succeed only if the job is
still `running` with the worker's fencing token. When a renewal updates nothing, the worker SHALL stop the execution within one
heartbeat interval and SHALL NOT change the job or write an effect. It only records its execution outcome.

#### Scenario: Lease taken away
- **WHEN** a worker's heartbeat finds that the job's fencing token has changed
- **THEN** the worker abandons the execution, records its execution outcome as `fenced` or `cancelled`, and does not write an effect

### Requirement: Self-fencing when the database is unreachable
A heartbeat that fails with an error SHALL be retried on the next tick. If no heartbeat has succeeded for longer than
the lease duration minus one heartbeat interval, measured by the worker's monotonic clock, the worker SHALL stop the execution.

#### Scenario: Database hiccup longer than the lease
- **WHEN** every heartbeat of a worker fails with a connection error for 5 seconds
- **THEN** the worker stops the execution on its own before another worker can legitimately claim the job

### Requirement: Periodic tasks survive errors
A heartbeat or reaper tick that throws SHALL be logged as an error and SHALL NOT stop later ticks.

#### Scenario: Reaper keeps running after an exception
- **WHEN** one reaper tick fails with a database error
- **THEN** the next tick runs and reaps expired jobs normally

### Requirement: Successful completion is fenced and atomic
A worker SHALL record the effect and move the job to `succeeded` in one database transaction, and only if the job is
still `running` with the worker's fencing token. If the check fails, the transaction SHALL roll back and leave no effect.

#### Scenario: Normal completion
- **WHEN** the current owner finishes the work
- **THEN** the job becomes `succeeded`, exactly one effect row exists, and the effect carries the owner's fencing token

#### Scenario: Zombie completion is rejected
- **WHEN** a worker that was paused longer than its lease resumes and tries to complete with an old fencing token
- **THEN** the completion changes nothing, no effect is written, and the job keeps the state set by its newer owner

### Requirement: At most one effect per job is enforced by the database
The database SHALL reject a second effect for the same job, independently of the fencing check,
so a bug in application code surfaces as an error and not as a silent duplicate.

#### Scenario: Duplicate effect insert
- **WHEN** any code path tries to insert a second effect for a job
- **THEN** the insert fails with a uniqueness violation and the transaction rolls back

### Requirement: Failed attempts are retried, then marked failed
When an execution fails, the current owner SHALL move the job back to `queued` with an exponential backoff on `run_after`
if attempts remain, or to `failed` with `lastError` set if `attempts` equals `maxAttempts` (default 3).
These updates SHALL be fenced like completion. `lastError` SHALL describe the most recent failed attempt.

#### Scenario: Retry
- **WHEN** the first attempt of a job fails
- **THEN** the job returns to `queued` with a later `run_after` and no effect

#### Scenario: Out of attempts
- **WHEN** the third attempt of a job fails
- **THEN** the job becomes `failed`, `lastError` describes the failure, and it has no effect

### Requirement: Dead workers are detected through lease expiry
A reaper SHALL run periodically in every API and worker process. It SHALL move each `running` job whose lease expired, by database time,
back to `queued`, or to `failed` if no attempts remain. A row locked by another transaction SHALL be skipped and not waited on,
and concurrent reapers SHALL NOT corrupt state.

#### Scenario: Killed worker
- **WHEN** the owner of a running job is killed and stops sending heartbeats
- **THEN** within lease duration plus two reaper intervals the job is back in `queued` with a new fencing token

#### Scenario: Stalled transaction does not stall reaping
- **WHEN** a paused worker holds a row lock inside an open transaction
- **THEN** other expired jobs are still reaped on schedule, and the stalled transaction is aborted by the database after 2 seconds of inactivity

#### Scenario: Poison job
- **WHEN** a job crashes its worker process on every attempt
- **THEN** after `maxAttempts` claims the job becomes `failed` instead of being retried forever

### Requirement: Maximum runtime
A job that has been `running` in the same attempt for longer than the maximum runtime (default 60 seconds)
SHALL be treated like an expired lease, even if heartbeats continue. A worker whose execution does not stop within 5 seconds
of being told to stop SHALL exit the process, so a hung execution cannot hold a job or a worker forever.

#### Scenario: Hung execution with a live heartbeat
- **WHEN** a worker's execution hangs while its heartbeat thread keeps running
- **THEN** after the maximum runtime the job is reaped with a new fencing token, and the worker process exits and is restarted

### Requirement: Cancellation stops the worker
When a running job is cancelled, its worker SHALL stop the execution within one heartbeat interval and SHALL NOT record an effect.

#### Scenario: Cancel observed on heartbeat
- **WHEN** a job is cancelled while its worker is executing it
- **THEN** the worker's next heartbeat fails, the worker abandons the execution, and the job stays `cancelled`

### Requirement: Terminal states are final
`succeeded`, `failed` and `cancelled` SHALL be terminal: no claim, heartbeat, completion, failure, reap or cancel SHALL change a job in a terminal state.

#### Scenario: Late completion after cancel
- **WHEN** a worker tries to complete a job that was cancelled
- **THEN** the job stays `cancelled` and no effect is written

### Requirement: Transition audit log
The database SHALL record every change of a job's status or fencing token, and every attempted change of a terminal job,
with the old and new status and token. It SHALL do this independently of application code, so a bug or a mutant cannot skip it.
Heartbeats SHALL NOT be recorded.

#### Scenario: Double claim leaves a trace
- **WHEN** a job already `running` is claimed again by a second worker
- **THEN** the audit log contains a `running → running` row with a token increase

### Requirement: Database time is the only clock
Lease expiry, reaping, maximum runtime and retry backoff SHALL be computed from the database clock only.
A worker's wall clock SHALL NOT influence any of these decisions. Only the monotonic clock is used for local self-fencing.

#### Scenario: Skewed worker clock
- **WHEN** a worker process's wall clock is one hour ahead of the database clock
- **THEN** its leases expire at the same database time as any other worker's, and all invariants still hold

### Requirement: Unique worker identity per process start
Each worker process start SHALL generate a new worker id that is made of the container hostname and a random suffix,
so a restarted container is never confused with its previous life, and a worker id can be mapped to its container.

#### Scenario: Restarted container
- **WHEN** a worker container is killed and started again
- **THEN** it runs under a worker id with the same hostname prefix and a different suffix

### Requirement: Graceful shutdown
On SIGTERM a worker SHALL stop claiming new jobs and let its current execution finish within a grace period (default 10 seconds).
If the execution does not finish in time, the worker SHALL exit and leave the job to lease expiry.

#### Scenario: Deploy restart
- **WHEN** a worker receives SIGTERM while executing a short job
- **THEN** the job completes normally and the worker exits without claiming another job

### Requirement: Emulated execution with a countable side effect
The execution SHALL emulate training by waiting a random time (default 100 to 500 ms) and SHALL fail with a configurable
probability (default 5%). On success the effect SHALL be a row that records the job id, fencing token and worker id.

#### Scenario: Effect row contents
- **WHEN** a job succeeds
- **THEN** its effect row contains the job id, the fencing token of the winning attempt and the id of the worker that ran it

### Requirement: Test hooks are off by default
Only when the worker setting `WORKER_TEST_HOOKS` is enabled, a payload field `simulate` SHALL control the emulator:
`crash` makes the worker process halt immediately, and `hang` makes the execution ignore stop requests.
With the setting off, `simulate` SHALL be ignored.

#### Scenario: Hooks disabled
- **WHEN** a job with `{"simulate": "crash"}` runs on a worker without `WORKER_TEST_HOOKS`
- **THEN** the job executes normally and the worker does not crash
