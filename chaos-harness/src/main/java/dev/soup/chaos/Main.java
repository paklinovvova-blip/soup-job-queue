package dev.soup.chaos;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Chaos harness entry point. Chaos mode:
 * <pre>
 * fresh stack → schema check → paced workload + kills + pauses + I10 sampling
 *   → faults until 95% terminal → heal → quiescence → I1–I9 → counters → verdict → exit code
 * </pre>
 * Exit code: 0 = run met its expectation, 2 = inconclusive (faults did not reach the dangerous paths), 1 = otherwise.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.ROOT); // 31.9%, not 31,9%: the logs are read in English
        Config cfg;
        try {
            cfg = Config.parse(args);
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            System.err.println(Config.USAGE);
            System.exit(1);
            return;
        }
        if (cfg == null) {
            System.out.println(Config.USAGE);
            return;
        }
        if (cfg.dryRun()) {
            System.out.println("dry run, nothing is started");
            System.out.println("config: " + cfg);
            System.out.println("plan:   " + Plan.summarize(Plan.generate(cfg.seed(), cfg.jobs())));
            return;
        }
        Path logPath = Out.open(cfg.runsDir(), cfg.label(), cfg.seed());
        int exit;
        try {
            exit = cfg.mode().equals("load") ? LoadRun.run(cfg, setUp(cfg)) : chaos(cfg);
        } catch (Exception e) {
            Out.line("HARNESS ERROR: %s", e);
            exit = 1;
        }
        Out.raw("");
        Out.raw("exit code: " + exit + "   raw log: " + logPath);
        Out.close();
        System.exit(exit);
    }

    /** Fresh stack and schema check. Returns the database handle, or throws if the stack is not right. */
    static Db setUp(Config cfg) throws Exception {
        Out.section("RUN");
        Out.raw("config:   " + cfg);
        Out.raw("revision: " + revision());
        Out.raw("note:     the seed fixes the workload and the fault schedule; thread scheduling and timing are not reproducible");
        Map<String, String> env = new HashMap<>();
        env.put("QUEUE_MUTANT", cfg.mode().equals("load") ? "none" : cfg.mutant());
        env.put("QUEUE_LEASE", cfg.lease().toMillis() + "ms");
        Docker docker = new Docker(env);
        if (cfg.fresh()) {
            Out.line("docker compose down -v");
            docker.composeDown();
            Out.line("docker compose up --scale worker=%d (QUEUE_MUTANT=%s)", cfg.workers(), env.get("QUEUE_MUTANT"));
            Docker.Result up = docker.composeUp(cfg.workers());
            if (!up.ok()) {
                throw new IllegalStateException("docker compose up failed: " + up.output());
            }
        }
        Db db = new Db(cfg.dbUrl(), cfg.dbUser(), cfg.dbPassword());
        Api api = new Api(cfg.api());
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (!(api.healthy() && db.reachable())) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("API or database not reachable");
            }
            Thread.sleep(1000);
        }
        waitForWorkers(docker, cfg.workers());
        long pk = db.count("SELECT count(*) FROM pg_constraint WHERE conrelid = 'effects'::regclass AND contype = 'p'");
        boolean expectPk = !cfg.mutant().equals("no-fencing") || cfg.mode().equals("load");
        Out.line("schema check: effects primary key present=%s, expected=%s", pk == 1, expectPk);
        if ((pk == 1) != expectPk) {
            throw new IllegalStateException("SCHEMA MISMATCH for mode " + cfg.mutant()
                    + ": run with --fresh true (docker compose down -v) so the schema matches the mode");
        }
        return db;
    }

    private static void waitForWorkers(Docker docker, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        int started = 0;
        while (System.nanoTime() < deadline) {
            String logs = docker.run(30, "docker", "compose", "logs", "worker").output();
            started = (int) logs.lines().filter(l -> l.contains(" started: ")).count();
            if (started >= expected) {
                break;
            }
            Thread.sleep(1000);
        }
        Out.line("workers started: %d of %d", started, expected);
    }

    private static int chaos(Config cfg) throws Exception {
        Db db = setUp(cfg);
        Docker docker = new Docker(Map.of());
        List<String> containers = docker.workerContainers();
        Out.line("worker containers: %s", containers.stream().map(Docker::shortId).collect(Collectors.joining(" ")));

        List<Plan.KeyPlan> plan = Plan.generate(cfg.seed(), cfg.jobs());
        Out.raw("plan:     " + Plan.summarize(plan));
        Set<String> submittedKeys = new HashSet<>();
        plan.forEach(k -> submittedKeys.add(k.key()));

        Api api = new Api(cfg.api());
        ClientLog clientLog = new ClientLog();
        Workload workload = new Workload(cfg, api, clientLog, db, plan);
        Faults faults = new Faults(cfg, docker, db, containers);
        Invariants.LeaseHorizon i10 = new Invariants.LeaseHorizon(db, cfg.lease());

        Out.section("CHAOS");
        long runStart = System.nanoTime();
        i10.start();
        faults.start();
        Thread progress = Thread.ofPlatform().name("progress").daemon().start(() -> progress(db, workload, faults));

        workload.run();
        Out.line("all %d keys and their follow-ups sent", plan.size());

        long deadline = System.nanoTime() + cfg.timeout().toNanos();
        while (terminalShare(db, plan.size()) < cfg.faultWindow() && System.nanoTime() < deadline) {
            Thread.sleep(1000);
        }
        Out.line("fault window over (%.1f%% terminal), stopping faults and healing workers", 100 * terminalShare(db, plan.size()));
        faults.stopAndHeal();
        workload.stopTargeting();

        Out.line("waiting for quiescence (timeout %s)", Out.human(cfg.timeout()));
        long quiesceDeadline = System.nanoTime() + cfg.timeout().toNanos();
        while (db.count("SELECT count(*) FROM jobs WHERE status IN ('queued', 'running')") > 0
                && System.nanoTime() < quiesceDeadline) {
            Thread.sleep(1000);
        }
        double runSeconds = (System.nanoTime() - runStart) / 1e9;
        i10.stop();
        progress.interrupt();

        List<ClientLog.Entry> entries = clientLog.entries();
        Path jsonl = Path.of(Out.path().toString().replaceAll("\\.log$", ".jsonl"));
        clientLog.writeJsonl(jsonl);

        Out.section("FAULT LOG");
        for (Faults.Fault f : faults.log()) {
            Out.raw(String.format("%8.1fs %-7s %s executing=%-5s %s", f.atSeconds(), f.kind(), f.container(),
                    f.victimWasExecuting(), f.detail()));
        }

        Out.section("INVARIANTS");
        List<Invariants.Check> checks = new ArrayList<>(new Invariants(db, entries, submittedKeys).afterQuiescence());
        checks.add(i10.result());
        for (Invariants.Check c : checks) {
            Out.raw(String.format("%-4s %-4s %-95s offending=%d%s", c.id(), c.passed() ? "PASS" : "FAIL", c.name(),
                    c.offending(), c.passed() ? "" : " e.g. " + c.sample()));
        }

        Out.section("COUNTERS");
        Map<String, Long> counters = counters(db, entries, faults.log(), workload, runSeconds);
        counters.forEach((k, v) -> Out.raw(String.format("%-45s %d", k, v)));
        Out.raw(String.format("%-45s %.1f", "throughput terminal jobs per second", counters.get("jobs total") / runSeconds));

        return verdict(cfg, checks, counters);
    }

    private static double terminalShare(Db db, int total) {
        long done = db.count("SELECT count(*) FROM jobs WHERE status IN ('succeeded', 'failed', 'cancelled')");
        return total == 0 ? 1 : (double) done / total;
    }

    private static void progress(Db db, Workload w, Faults faults) {
        while (true) {
            try {
                Thread.sleep(10_000);
                Map<String, String> byStatus = db.pairs("SELECT status, count(*)::text FROM jobs GROUP BY 1 ORDER BY 1");
                long kills = faults.log().stream().filter(f -> f.kind() == Faults.Kind.KILL).count();
                long pauses = faults.log().stream().filter(f -> f.kind() == Faults.Kind.PAUSE).count();
                Out.line("progress: submitted=%d jobs=%s kills=%d pauses=%d targeted-cancels-sent=%d",
                        w.submitted.get(), byStatus, kills, pauses, w.targetedSent.get());
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                Out.line("progress: %s", e.getMessage());
            }
        }
    }

    static Map<String, Long> counters(Db db, List<ClientLog.Entry> log, List<Faults.Fault> faults, Workload w,
                                      double runSeconds) {
        Map<String, Long> c = new LinkedHashMap<>();
        c.put("jobs total", db.count("SELECT count(*) FROM jobs"));
        c.put("jobs succeeded", db.count("SELECT count(*) FROM jobs WHERE status = 'succeeded'"));
        c.put("jobs failed", db.count("SELECT count(*) FROM jobs WHERE status = 'failed'"));
        c.put("jobs failed by lease loss (innocent)", db.count(
                "SELECT count(*) FROM jobs WHERE status = 'failed' AND last_error LIKE 'lease expired%'"));
        c.put("jobs cancelled", db.count("SELECT count(*) FROM jobs WHERE status = 'cancelled'"));
        c.put("kills", faults.stream().filter(f -> f.kind() == Faults.Kind.KILL).count());
        c.put("kills of an executing worker", faults.stream().filter(f -> f.kind() == Faults.Kind.KILL && f.victimWasExecuting()).count());
        c.put("pauses", faults.stream().filter(f -> f.kind() == Faults.Kind.PAUSE).count());
        c.put("pauses of an executing worker", faults.stream().filter(f -> f.kind() == Faults.Kind.PAUSE && f.victimWasExecuting()).count());
        c.put("pause attempts that missed (worker idle)", faults.stream().filter(f -> f.kind() == Faults.Kind.PAUSE_MISSED).count());
        c.put("executions", db.count("SELECT count(*) FROM executions"));
        c.put("executions interrupted (worker died)", db.count("SELECT count(*) FROM executions WHERE outcome IS NULL"));
        c.put("executions fenced (zombie or lost lease)", db.count("SELECT count(*) FROM executions WHERE outcome = 'fenced'"));
        c.put("executions stopped by cancel", db.count("SELECT count(*) FROM executions WHERE outcome = 'cancelled'"));
        c.put("executions failed (emulated error)", db.count("SELECT count(*) FROM executions WHERE outcome = 'failed'"));
        c.put("jobs with more than one execution", db.count(
                "SELECT count(*) FROM (SELECT job_id FROM executions GROUP BY job_id HAVING count(*) > 1) t"));
        c.put("cancels that hit a queued job", db.count(
                "SELECT count(*) FROM job_transitions WHERE old_status = 'queued' AND new_status = 'cancelled'"));
        c.put("cancels that hit a running job", db.count(
                "SELECT count(*) FROM job_transitions WHERE old_status = 'running' AND new_status = 'cancelled'"));
        c.put("cancel requests sent", log.stream().filter(e -> e.type().equals("cancel")).count());
        c.put("cancel answered 409 job_finished", log.stream().filter(e -> e.type().equals("cancel") && e.code() == 409).count());
        c.put("targeted cancels sent", (long) w.targetedSent.get());
        c.put("creates answered 201", log.stream().filter(e -> isCreate(e) && e.code() == 201).count());
        c.put("idempotent replays answered 200", log.stream().filter(e -> isCreate(e) && e.code() == 200).count());
        c.put("concurrent duplicates answered 200", log.stream().filter(e -> e.concurrent() && e.code() == 200).count());
        c.put("conflicting resends answered 409", log.stream().filter(e -> e.type().equals("conflict") && e.code() == 409).count());
        c.put("requests timed out (unknown)", log.stream().filter(e -> e.code() < 0).count());
        c.put("requests answered 5xx", log.stream().filter(e -> e.code() >= 500).count());
        c.put("run seconds", (long) runSeconds);
        return c;
    }

    private static boolean isCreate(ClientLog.Entry e) {
        return e.type().equals("create") || e.type().equals("resend");
    }

    /** Spec: "Evidence that faults had an effect" and "Seeded-bug modes". */
    static int verdict(Config cfg, List<Invariants.Check> checks, Map<String, Long> c) {
        List<String> failed = checks.stream().filter(ch -> !ch.passed()).map(Invariants.Check::id).toList();
        Out.section("VERDICT");
        if (!cfg.mutant().equals("none")) {
            if (failed.isEmpty()) {
                Out.raw("EXPECTED FAILURE: NOT CAUGHT. Mutant " + cfg.mutant() + " passed every invariant; expected " + cfg.expectedCatch());
                return 1;
            }
            Out.raw("EXPECTED FAILURE: CAUGHT. Mutant " + cfg.mutant() + " violated " + failed + " (expected " + cfg.expectedCatch() + ")");
            return 0;
        }
        if (!failed.isEmpty()) {
            Out.raw("FAIL: invariants violated: " + failed);
            return 1;
        }
        List<String> toothless = new ArrayList<>();
        if (c.get("kills of an executing worker") == 0) {
            toothless.add("no kill interrupted an execution");
        }
        if (c.get("executions fenced (zombie or lost lease)") == 0) {
            toothless.add("no execution was fenced");
        }
        if (c.get("cancels that hit a running job") < 50) {
            toothless.add("fewer than 50 cancels hit a running job (" + c.get("cancels that hit a running job") + ")");
        }
        if (c.get("concurrent duplicates answered 200") == 0) {
            toothless.add("no concurrent duplicate was answered 200");
        }
        if (!toothless.isEmpty()) {
            Out.raw("INCONCLUSIVE: all invariants passed, but the faults did not reach the dangerous paths: " + toothless);
            return 2;
        }
        Out.raw("PASS: all invariants held and the faults reached the dangerous paths");
        return 0;
    }

    private static String revision() {
        Docker git = new Docker(Map.of());
        String rev = git.run(10, "git", "rev-parse", "--short", "HEAD").output();
        String dirty = git.run(10, "git", "status", "--porcelain", "--untracked-files=no").output();
        return rev + (dirty.isBlank() ? "" : "-dirty");
    }
}
