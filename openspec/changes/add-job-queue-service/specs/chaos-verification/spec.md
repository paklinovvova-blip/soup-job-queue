# Spec Delta

## Purpose

Produces evidence that the queue keeps its guarantees under failure: a chaos harness that injects worker crashes,
zombies, duplicate requests and cancellations, checks invariants, proves it can catch seeded bugs, and keeps every run's raw output.

## ADDED Requirements

### Requirement: Paced chaos workload
A chaos run SHALL submit N jobs with distinct idempotency keys (default 10,000) through the public API, paced at a configurable rate
(default 15 creates per second, below the drain rate), so the queue stays shallow and faults overlap with execution for the whole run.
At least 30% of keys SHALL also be sent 2 or 3 times, concurrently or as delayed retries, and about 1% SHALL be resent with a different payload.

#### Scenario: Default run
- **WHEN** the harness runs with default settings
- **THEN** it submits 10,000 distinct keys at about 15 per second, plus duplicates and conflicting resends, while faults are injected

### Requirement: Cancellations that hit both states
About 10% of jobs SHALL be cancelled. About half of the cancels SHALL go to random jobs after a random delay,
and the other half SHALL target jobs observed as `running`, sent 0 to 400 ms after the harness sees that state.

#### Scenario: Cancels during execution
- **WHEN** a chaos run completes
- **THEN** its output reports how many cancels hit a queued job and how many hit a running one, counted from execution outcomes

### Requirement: Worker crash injection
During a chaos run the harness SHALL send SIGKILL to a worker about every 2 seconds, with jitter, and start it again after 0.5 to 1.5 seconds.
With probability 0.8 the victim SHALL be chosen among workers that currently own a running job, otherwise at random.
Every kill and restart SHALL be logged with a timestamp and whether the victim was executing.

#### Scenario: Kills during execution
- **WHEN** a chaos run completes
- **THEN** its output reports the number of kills and how many of them interrupted a running execution

### Requirement: Zombie injection
During a chaos run the harness SHALL pause a worker that currently owns a running job about every 15 seconds,
for longer than the lease duration, and then resume it, so the resumed worker holds a stale fencing token.

#### Scenario: Zombie writes are rejected
- **WHEN** a paused worker resumes after its job was reclaimed
- **THEN** the run output shows a non-zero count of fenced executions and no invariant violation

### Requirement: Fault window and quiescence
Faults SHALL continue until at least 95% of jobs are terminal. Then the harness SHALL stop faults, resume and start every worker,
and wait until no job is `queued` or `running`, up to a timeout (default 15 minutes).
Reaching the timeout SHALL count as a violation of I4.

#### Scenario: Stuck job
- **WHEN** a job is still `running` when the timeout expires
- **THEN** the run is reported as failed and the stuck job ids are listed

### Requirement: Client log and request timeouts
The harness SHALL log every request and response, including the key, status code, job id and reported state.
Requests SHALL time out after 15 seconds. A timed-out request SHALL be logged as `unknown` and never counted as success or failure.
A key is accepted when at least one of its creates got 200 or 201.

#### Scenario: Create timed out
- **WHEN** every create for a key timed out
- **THEN** I1 accepts either outcome for that key: no job, or exactly one job

### Requirement: Job and effect invariants
After quiescence the harness SHALL check in the database: I1 every accepted key has exactly one job and no job lacks a submitted key;
I2 at most one effect per job; I3 `succeeded` if and only if one effect, `failed` and `cancelled` have none; I4 no job left `queued` or `running`;
I8 attempts never exceed the maximum and every `failed` job used all attempts.

#### Scenario: Duplicate effect detected
- **WHEN** a job has two effect rows
- **THEN** I2 is reported as failed with that job id

### Requirement: Response and ownership invariants
The harness SHALL check its client log against the database: I5 every cancel response matches the final state;
I6 all responses for one key carry the same job id, `jobId` of 409 conflicts included; I7 each effect's token equals its job's final token
and matches an execution with outcome `succeeded`, and every execution of a `cancelled` job has a token below the job's final token.

#### Scenario: Cancel response contradicted
- **WHEN** a cancel request got 200 `cancelled` but the job ended `succeeded`
- **THEN** I5 is reported as failed with that job id

### Requirement: Legal transitions invariant
I9: every row of the transition audit log SHALL be one of the legal edges: `queued → running`, `running → queued`, `running → failed`,
`queued → cancelled` and `running → cancelled`, each with a token increase, or `running → succeeded` with an unchanged token.
Any other row is a violation.

#### Scenario: Double claim detected
- **WHEN** the audit log contains a `running → running` row
- **THEN** I9 is reported as failed with that job id

#### Scenario: Clean run
- **WHEN** a chaos run against the correct implementation finishes
- **THEN** every invariant I1 to I10 is reported as passed

#### Scenario: Violation report
- **WHEN** an invariant fails
- **THEN** the output names the invariant, the number of offending jobs and up to 20 of their ids

### Requirement: Lease horizon invariant
I10: once per second during the run the harness SHALL check that no `running` job has a lease expiry later than database time
plus twice the lease duration. Any sample with such a job is a violation.

#### Scenario: Lease far in the future
- **WHEN** a worker writes a lease expiry one hour ahead of database time
- **THEN** I10 is reported as failed within a few seconds, with that job id

### Requirement: Evidence that faults had an effect
Each run SHALL report counters: kills, kills that interrupted an execution, pauses, executions, jobs with more than one execution,
fenced executions, cancels of queued and of running jobs, idempotent replays, concurrent duplicates answered 200, conflicts, timeouts, duration and throughput.
A run SHALL be flagged inconclusive if no kill interrupted an execution, no execution was fenced, fewer than 50 cancels hit running jobs,
or no concurrent duplicate got 200.

#### Scenario: Toothless run
- **WHEN** a run passes all invariants but fewer than 50 cancels hit running jobs
- **THEN** the output flags the run as inconclusive and the harness exits with code 2

### Requirement: Seeded-bug modes
The system SHALL offer mutant modes, enabled only by an explicit setting given to both the API and worker containers and logged loudly at startup:
`naive-claim` (read, then update without a row lock), `no-fencing` (heartbeat, completion and failure ignore the fencing token, and the unique effect constraint is absent)
and `worker-clock-lease` (lease expiry computed from a worker clock skewed one hour ahead). A run in each mutant mode SHALL be expected to fail.

#### Scenario: Harness catches the naive claim
- **WHEN** a chaos run is executed with `naive-claim`
- **THEN** I9 fails and the output marks the result as "expected failure: caught"

#### Scenario: Harness catches missing fencing
- **WHEN** a chaos run is executed with `no-fencing`
- **THEN** I2 or I5 fails and the output marks the result as "expected failure: caught"

#### Scenario: Harness catches the worker-clock lease
- **WHEN** a chaos run is executed with `worker-clock-lease`
- **THEN** I10 fails and the output marks the result as "expected failure: caught"

#### Scenario: Mutant not caught
- **WHEN** a chaos run in a mutant mode passes all invariants
- **THEN** the output marks the result as "expected failure: NOT caught" and the harness exits with a non-zero code

### Requirement: Schema matches the mode
At the start of each run the harness SHALL verify that the database schema matches the mode: the unique effect constraint exists
in every mode except `no-fencing`, and is absent in `no-fencing`. A mismatch SHALL abort the run before any job is submitted.

#### Scenario: Leftover mutant schema
- **WHEN** a normal run starts on a database without the unique effect constraint
- **THEN** the harness aborts with a schema mismatch error and submits nothing

### Requirement: Real clock-skew run
The harness SHALL support a run with the normal implementation where some worker processes run with their wall clock shifted by one hour.
The shift SHALL be applied to the process clock, not only to an injected clock object, and the run SHALL log each worker's wall clock next to
the database time to prove the shift is real. All invariants SHALL pass.

#### Scenario: Skewed workers
- **WHEN** a chaos run uses workers whose process clock is one hour ahead
- **THEN** the log shows the one-hour difference and all invariants pass

### Requirement: Raw output is kept for every run
Each run SHALL write its full raw output to `runs/<UTC timestamp>-<mode>-seed<seed>.log` and SHALL never overwrite or delete earlier files.
The output SHALL include configuration, seed, code revision when available, fault log summary, counters, invariant results and the final verdict.
Exit code: 0 if the run met its expectation, 2 if inconclusive, 1 otherwise.

#### Scenario: Failed run kept
- **WHEN** a run fails
- **THEN** its log file stays in `runs/` next to the passing runs

### Requirement: Reproducible workload
A seed SHALL determine the workload and the fault schedule, so a run can be repeated with the same inputs.
Output SHALL state that thread scheduling and timing are not reproducible.

#### Scenario: Same seed
- **WHEN** two runs use the same seed
- **THEN** they submit the same keys, duplicates and cancellations and plan the same fault schedule

### Requirement: Load run with pre-registered criteria
The harness SHALL provide a load mode at a target rate (default 100 requests per second) for a set time (default 5 minutes), with the mix fixed
before the run: 20% create, 60% get, 15% list, 5% cancel. It SHALL report p50, p95, p99 latency, error rate and queue depth over time,
and SHALL judge SHIP when p99 < 200 ms, 5xx < 0.1% and queue depth does not grow over the last 3 minutes.

#### Scenario: 100 RPS run
- **WHEN** the load mode runs at 100 requests per second for 5 minutes
- **THEN** the output contains the percentiles, error rate, queue depth trend and the SHIP or DON'T SHIP judgement against the fixed criteria

#### Scenario: 100 creates per second
- **WHEN** the load mode runs with 100% creates at 100 per second
- **THEN** the output reports the queue growth rate, which is the evidence for the capacity part of the verdict
