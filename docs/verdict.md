# Verdict at 100 requests per second

## Short answer

**DON'T SHIP yet.** The queue mechanics are correct under failure, and the API handles 100 rps with a large margin.
But if "100 requests per second" means 100 new training jobs per second, 8 single-job workers cannot drain them:
the queue grows by about 75 jobs every second. That is the first thing to fix. After it, re-run the load and the chaos test at the new scale.

"100 rps" is ambiguous, so we measured both readings, with criteria fixed in the spec **before** the runs:
p99 < 200 ms, errors < 0.1%, queue depth not growing over the last 3 minutes.

| Run | p99 | Errors | Queue depth | Result |
|---|---|---|---|---|
| 100 rps API traffic: 20% create / 60% get / 15% list / 5% cancel, 5 min ([log](../runs/20261110T151905Z-load-seed1.log)) | 7.7 ms | 0 / 30,000 | flat (0–5 queued) | **SHIP** |
| 100 creates/s, 5 min ([log](../runs/20261110T152423Z-load-creates-seed1.log)) | 6.0 ms | 0 / 30,000 | 0 → 22,397 queued, **+74.8 jobs/s** | **DON'T SHIP** |

Correctness under failure comes from three 10,000-job chaos runs: all ten invariants held. Two seeded bugs were each caught. See [`chaos.md`](chaos.md).

## Why the queue grows: the capacity math

- One job takes 100–500 ms, 300 ms on average. One worker process runs one job at a time (design choice D12).
- 8 workers → about 8 / 0.3 s ≈ **27 jobs/s** at best. The measured outflow was about 100 − 74.8 ≈ **25 jobs/s**.
- **Little's law** for 100 jobs/s: in flight = arrival rate × time in system = 100 × 0.3 s = **30 executions at once**. With
  headroom for kills, restarts and retries, that means about **40 worker slots**, 5× what we run.
- With real training jobs (minutes to hours, not 300 ms) the gap is bigger still. Any worker count must be sized from job duration.

The API is not the bottleneck: it answered 100 creates/s with a 6 ms p99 while the backlog grew.

## What to fix first

1. **Worker capacity.** Give each worker a small thread pool (4 slots × 10 containers), or run about 40 single-slot workers, then repeat both load runs.
   The design already supports more workers. What changes is the reasoning about kills: one kill now interrupts up to 4 jobs.
2. **Re-run the chaos test at that scale**, with the pace raised close to the new drain rate. More workers means more claims, heartbeats and transitions per second on one Postgres.
   Measure it, do not assume it.
3. **Retention.** At 100 jobs/s, `jobs` grows by 8.6 M rows a day, plus `executions` and `job_transitions` (3–4 rows per job).
   Finished jobs need to be archived or partitioned away, and the heartbeat-heavy table needs autovacuum and `fillfactor` tuning.
4. **Observe it in production:** queue depth, age of the oldest queued job, reaped and fenced rates, jobs failed by lease loss. All of these exist today only as harness counters.
5. **Before real traffic:** Postgres high availability, plus a failover test (the harness never kills Postgres or the API), authentication and rate limits on the API, and `LISTEN/NOTIFY` instead of polling once there are 40+ workers.

## What surprised me, and what still concerns me

> **Draft. The author rewrites this paragraph in their own words before submission.**
>
> What surprised me most is how well a correct-looking test can hide a real bug. The deliberately broken claim produced
> 357 double claims in one run, and still not a single job had two effects. Fencing quietly cleaned up after the bug. Only
> the audit log of state transitions showed it. A chaos test that counted effects would have called a broken queue correct.
> The same thing happened to our own harness: its first version "paused" workers that had already finished their job, so it
> tested zero zombies and still looked green. The inconclusive rule caught that. What still concerns me is everything the
> harness never touches. Postgres is a single point of failure that we never killed, the API process was never killed
> mid-request, there were no network partitions, and the effect lives in the same database as the job state. With a
> real external effect, the exactly-once claim falls back to at-least-once. And the 1 ms commit races are covered by a
> targeted unit test, not by chaos.
