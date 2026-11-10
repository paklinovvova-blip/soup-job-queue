package dev.soup.chaos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fault injector (spec: "Worker crash injection", "Zombie injection").
 * <ul>
 * <li>SIGKILL a worker about every 2 s, then {@code docker start} it after 0.5–1.5 s. Docker does not
 *     restart a container after {@code docker kill} on its own (checked on this stack), so we do it.</li>
 * <li>Pause a worker for lease + 3 s about every 15 s, so it wakes up holding a stale token.</li>
 * </ul>
 * With probability 0.8 the victim is a worker that currently owns a running job; a kill or pause of an
 * idle worker proves little. The schedule (intervals, coin flips) is seeded; who is executing is not.
 */
final class Faults {

    enum Kind { KILL, START, PAUSE, PAUSE_MISSED, UNPAUSE }

    record Fault(double atSeconds, Kind kind, String container, boolean victimWasExecuting, String detail) {
    }

    private enum State { RUNNING, KILLED, PAUSED }

    private final Config cfg;
    private final Docker docker;
    private final Db db;
    private final List<String> containers;
    private final Map<String, State> state = new ConcurrentHashMap<>();
    private final List<Fault> log = new CopyOnWriteArrayList<>();
    private final SplittableRandom killRandom;
    private final SplittableRandom pauseRandom;
    private volatile boolean running = true;
    private Thread killer;
    private Thread pauser;

    Faults(Config cfg, Docker docker, Db db, List<String> containers) {
        this.cfg = cfg;
        this.docker = docker;
        this.db = db;
        this.containers = List.copyOf(containers);
        containers.forEach(c -> state.put(c, State.RUNNING));
        this.killRandom = new SplittableRandom(cfg.seed() * 31 + 7);
        this.pauseRandom = new SplittableRandom(cfg.seed() * 17 + 3);
    }

    void start() {
        if (!cfg.killEvery().isZero()) {
            killer = Thread.ofPlatform().name("faults-kill").daemon().start(this::killLoop);
        }
        if (!cfg.pauseEvery().isZero()) {
            pauser = Thread.ofPlatform().name("faults-pause").daemon().start(this::pauseLoop);
        }
    }

    List<Fault> log() {
        return List.copyOf(log);
    }

    /** Stop injecting, then make sure every worker is running and unpaused before quiescence. */
    void stopAndHeal() throws InterruptedException {
        running = false;
        if (killer != null) {
            killer.interrupt();
            killer.join(10_000);
        }
        if (pauser != null) {
            pauser.interrupt();
            pauser.join(15_000);
        }
        for (String c : containers) {
            if ("paused".equals(docker.state(c))) {
                docker.unpause(c);
                record(Kind.UNPAUSE, c, false, "heal");
            }
            if (!"running".equals(docker.state(c))) {
                docker.start(c);
                record(Kind.START, c, false, "heal");
            }
            state.put(c, State.RUNNING);
        }
    }

    private void killLoop() {
        while (running) {
            try {
                Thread.sleep(jittered(killRandom, cfg.killEvery().toMillis(), 0.3));
                boolean preferExecuting = killRandom.nextDouble() < 0.8;
                Set<String> executingNow = executingContainers();
                String victim = claimVictim(preferExecuting, killRandom, executingNow, State.KILLED);
                if (victim == null) {
                    continue;
                }
                boolean executing = executingNow.contains(victim);
                Docker.Result r = docker.kill(victim);
                record(Kind.KILL, victim, executing, r.ok() ? "" : r.output());
                long restartAfter = 500 + killRandom.nextLong(1001);
                Thread.ofVirtual().start(() -> {
                    try {
                        Thread.sleep(restartAfter);
                    } catch (InterruptedException ignored) {
                        // restart anyway
                    }
                    Docker.Result s = docker.start(victim);
                    state.put(victim, State.RUNNING);
                    record(Kind.START, victim, false, s.ok() ? "after " + restartAfter + "ms" : s.output());
                });
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                Out.line("faults: kill step failed, continuing: %s", e.getMessage());
            }
        }
    }

    /**
     * A pause only makes a zombie if the worker is executing at the moment the pause lands, and a
     * 100–500 ms job is often over before the docker CLI call completes (first 1000-job dev run:
     * 3 pauses, 0 zombies). So after pausing we check again: if the container owns no running job,
     * the pause missed, we unpause at once and retry shortly. Intervals are start-to-start.
     */
    private void pauseLoop() {
        long pauseFor = cfg.lease().toMillis() + 3000;
        long nextAt = System.currentTimeMillis() + jittered(pauseRandom, cfg.pauseEvery().toMillis(), 0.33);
        while (running) {
            String victim = null;
            try {
                Thread.sleep(Math.max(0, nextAt - System.currentTimeMillis()));
                Set<String> executingNow = executingContainers();
                victim = claimVictim(pauseRandom.nextDouble() < 0.8, pauseRandom, executingNow, State.PAUSED);
                if (victim == null) {
                    nextAt = System.currentTimeMillis() + 1000;
                    continue;
                }
                long pausedAt = System.currentTimeMillis();
                Docker.Result r = docker.pause(victim);
                if (!r.ok() || !executingContainers().contains(victim)) {
                    Docker.Result u = docker.unpause(victim);
                    state.put(victim, State.RUNNING);
                    record(Kind.PAUSE_MISSED, victim, false, r.ok() ? "worker was idle when the pause landed" : r.output());
                    victim = null;
                    nextAt = System.currentTimeMillis() + 500;
                    continue;
                }
                record(Kind.PAUSE, victim, true, "for " + pauseFor + "ms");
                Thread.sleep(pauseFor);
                nextAt = pausedAt + jittered(pauseRandom, cfg.pauseEvery().toMillis(), 0.33);
            } catch (InterruptedException e) {
                running = false; // fall through to unpause, then stop
            } catch (RuntimeException e) {
                Out.line("faults: pause step failed, continuing: %s", e.getMessage());
            } finally {
                if (victim != null && state.get(victim) == State.PAUSED) {
                    Docker.Result u = docker.unpause(victim);
                    state.put(victim, State.RUNNING);
                    record(Kind.UNPAUSE, victim, false, u.ok() ? "" : u.output());
                }
            }
        }
    }

    /**
     * Pick a running (not killed, not paused) container, preferably one that owns a running job, and
     * mark it in one step, so the killer and the pauser never pick the same container at once.
     */
    private synchronized String claimVictim(boolean preferExecuting, SplittableRandom random, Set<String> executingNow,
                                            State next) {
        if (!running) {
            return null;
        }
        String victim = pick(preferExecuting, random, executingNow);
        if (victim != null) {
            state.put(victim, next);
        }
        return victim;
    }

    private String pick(boolean preferExecuting, SplittableRandom random, Set<String> executingNow) {
        List<String> available = new ArrayList<>();
        for (String c : containers) {
            if (state.get(c) == State.RUNNING) {
                available.add(c);
            }
        }
        if (available.isEmpty()) {
            return null;
        }
        if (preferExecuting) {
            List<String> executing = new ArrayList<>(available);
            executing.retainAll(executingNow);
            if (!executing.isEmpty()) {
                return executing.get(random.nextInt(executing.size()));
            }
        }
        return available.get(random.nextInt(available.size()));
    }

    /** Worker id = "<hostname>-<suffix>" and the hostname is the 12-char container id. */
    private Set<String> executingContainers() {
        Set<String> out = new HashSet<>();
        for (String owner : db.strings("SELECT DISTINCT lease_owner FROM jobs WHERE status = 'running' AND lease_owner IS NOT NULL")) {
            String host = owner.substring(0, Math.max(0, owner.lastIndexOf('-')));
            for (String c : containers) {
                if (!host.isEmpty() && c.startsWith(host)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private void record(Kind kind, String container, boolean executing, String detail) {
        Fault f = new Fault(Out.elapsedSeconds(), kind, Docker.shortId(container), executing, detail);
        log.add(f);
        if (kind == Kind.KILL || kind == Kind.PAUSE) {
            Out.line("fault %-7s %s executing=%s %s", kind, f.container(), executing, detail);
        }
    }

    private static long jittered(SplittableRandom r, long meanMs, double spread) {
        double factor = 1 - spread + r.nextDouble() * 2 * spread;
        return Math.max(50, (long) (meanMs * factor));
    }
}
