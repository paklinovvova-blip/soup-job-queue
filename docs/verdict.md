# Verdict at 100 requests per second

## DON'T SHIP — yet

The queue is **correct under failure**, and the API easily handles 100 requests per second. It is **not ready for
100 new training jobs per second**: 8 workers that run one job each drain about 25 jobs/s, so the backlog grows by
about 75 jobs every second and never recovers. The first fix is worker capacity. The other items below have to be
done before real traffic, because the chaos test never exercised them.

"100 requests per second" can be read in two ways, so both were measured. The criteria were written into the spec
before either run: p99 < 200 ms, errors < 0.1%, queue depth not growing over the last 3 minutes.

| Reading | p99 | Errors | Queue | Result |
|---|---|---|---|---|
| 100 rps of API traffic: 20% create, 60% get, 15% list, 5% cancel ([log](../runs/20261110T151905Z-load-seed1.log)) | 7.7 ms | 0 of 30,000 | flat, 0–5 queued | meets all criteria |
| 100 new jobs per second ([log](../runs/20261110T152423Z-load-creates-seed1.log)) | 6.0 ms | 0 of 30,000 | 0 → 22,397 in 5 min, **+74.8 jobs/s** | fails: queue grows without bound |

For a training-job queue I take the stricter reading: every request may be a new job. The verdict is therefore
DON'T SHIP. If the expected traffic is the mixed one (about 20 new jobs per second), the system meets the criteria today,
and the verdict depends only on the production gaps listed under "Before real traffic".

## What is ready

Three 10,000-job chaos runs ended with all ten invariants holding:

- **The setup:** 8 workers, a `kill -9` about every 2 s, a pause past the lease about every 15 s, plus duplicate POSTs, conflicting resends and cancels running in parallel.
- **Per run:** about 250 executions were cut off by a kill, 34–39 zombies came back with a stale token and were fenced, and 240–290 cancels hit a running job.
- **Results:** no job was lost, none had two effects, none was left running, and every cancel response matched the final state.
- **The seeded bugs were caught.** A claim without a lock produced 357 double claims. Removing fencing produced 28 double effects. Details are in [`chaos.md`](chaos.md).

## Why the queue grows

- One emulated job takes 100–500 ms, about 300 ms on average. Each worker process runs one job at a time.
- 8 workers × (1 / 0.3 s) ≈ **27 jobs/s** in theory. The measured drain rate was 100 − 74.8 ≈ **25 jobs/s**. The difference comes from claims, commits and idle polling.
- **Little's law:** jobs in flight = arrival rate × time in system = 100 / s × 0.3 s = **30 at once**. With headroom for kills, restarts and retries, that is about **40 worker slots**, five times what runs today.
- The API is not the bottleneck: it accepted 100 jobs/s with a 6 ms p99 while the backlog grew.
- Real training jobs take minutes, not 300 ms. The worker count has to be sized from the real job duration, and the same formula applies.

## What to fix first

1. **Worker capacity.** Raise it to about 40 slots, for example 10 containers with 4 threads each, then repeat both load runs.
   A thread pool changes one thing in the reasoning about failures: one `kill -9` can now interrupt up to 4 jobs. The guarantees stay the same, because every write is fenced per job.
2. **Re-run the chaos test at that scale.** Pace it close to the new drain rate. Forty workers mean about 5× more claims, heartbeats, commits and audit rows per second on one Postgres. That load has to be measured, not assumed.
3. **Make the backlog visible.** Today queue depth, age of the oldest queued job, reaped and fenced rates, and jobs failed by lease loss exist only as harness counters. In production they need metrics and alerts, or a growing backlog like the one above goes unnoticed.

## Before real traffic

- **Postgres high availability, with a failover test.** Postgres is a single point of failure, and the harness never kills it.
- **Kill the API mid-request in a test.** Idempotency covers this case on paper, but it was never injected.
- **Retention.** At 100 jobs/s, `jobs` grows by about 8.6 M rows a day, plus 1 row per run in `executions` and 3–4 rows per job in `job_transitions`.
  Finished jobs need archiving or partitioning. The heartbeat-heavy table needs autovacuum and `fillfactor` tuning.
- **`LISTEN/NOTIFY` instead of polling** once there are dozens of idle workers.
- **Authentication and rate limits** on the API.

## What would make it SHIP

The load run at 100 new jobs/s meets the three criteria on the scaled worker fleet, and a chaos run at that scale again holds all ten invariants. A failover test of Postgres would also have to keep them.

## What surprised me, and what still concerns me

What surprised me most is how well a correct-looking check can hide a real bug. The deliberately broken claim produced
357 double claims in one run, and still no job had two effects. Fencing silently undid the damage. Only the audit log
of state transitions showed that anything was wrong, so a chaos test that counted only effects would have called a
broken queue correct. The same thing happened to the harness itself. Its first version paused workers that had already
finished their 100 ms job, so it tested zero zombies and still looked green. Only the rule "no fenced executions means inconclusive" exposed it.
What still concerns me is what the tests never touch. Postgres was never killed, the API was never killed mid-request,
and there were no network partitions. The exactly-once effect also holds only because the effect lives in the same
database as the job state: with a real external effect, such as a model written to S3, it becomes at-least-once. The 1 ms
commit races are covered by a targeted concurrency test, not by chaos timing.
