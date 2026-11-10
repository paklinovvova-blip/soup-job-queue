package dev.soup.queue.worker;

import java.util.SplittableRandom;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.soup.queue.QueueProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emulated training (design D13): sleeps a random 100–500 ms in slices of at most 50 ms,
 * checking the stop flag between slices, and fails with a configured probability.
 * The randomness is seeded by (job id, fencing token), so one attempt behaves the same on any worker.
 */
public class TrainingEmulator {

    private static final Logger log = LoggerFactory.getLogger(TrainingEmulator.class);
    static final long SLICE_MS = 50;

    enum Outcome { COMPLETED, FAILED, STOPPED }

    record Result(Outcome outcome, String error) {
        static final Result COMPLETED = new Result(Outcome.COMPLETED, null);
        static final Result STOPPED = new Result(Outcome.STOPPED, null);

        static Result failed(String error) {
            return new Result(Outcome.FAILED, error);
        }
    }

    record Plan(long durationMs, boolean fails) {
    }

    private final QueueProperties.Emulator config;
    private final boolean testHooks;
    private final Exiter exiter;
    private final ObjectMapper mapper = new ObjectMapper();

    public TrainingEmulator(QueueProperties.Emulator config, boolean testHooks, Exiter exiter) {
        this.config = config;
        this.testHooks = testHooks;
        this.exiter = exiter;
    }

    static Plan plan(UUID jobId, long token, QueueProperties.Emulator cfg) {
        long seed = jobId.getMostSignificantBits() ^ Long.rotateLeft(jobId.getLeastSignificantBits(), 17)
                ^ (token * 0x9E3779B97F4A7C15L);
        SplittableRandom random = new SplittableRandom(seed);
        long min = cfg.minDuration().toMillis();
        long max = Math.max(min, cfg.maxDuration().toMillis());
        long duration = min + random.nextLong(max - min + 1);
        boolean fails = random.nextDouble() < cfg.failureProbability();
        return new Plan(duration, fails);
    }

    Result run(UUID jobId, long token, String payload, Attempt attempt) {
        if (testHooks) {
            String simulate = simulateField(payload);
            if ("crash".equals(simulate)) {
                log.warn("test hook: simulating a crash on job {}", jobId);
                exiter.halt(137);
            } else if ("hang".equals(simulate)) {
                log.warn("test hook: simulating a hung execution on job {}", jobId);
                return hangIgnoringStop();
            }
        }
        Plan plan = plan(jobId, token, config);
        long deadline = System.nanoTime() + plan.durationMs() * 1_000_000;
        try {
            while (true) {
                if (attempt.stopRequested()) {
                    return Result.STOPPED;
                }
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
                if (remainingMs <= 0) {
                    break;
                }
                Thread.sleep(Math.min(SLICE_MS, remainingMs));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.STOPPED;
        }
        if (attempt.stopRequested()) {
            return Result.STOPPED;
        }
        return plan.fails() ? Result.failed("emulated training failure") : Result.COMPLETED;
    }

    private Result hangIgnoringStop() {
        try {
            while (true) {
                Thread.sleep(SLICE_MS); // deliberately never looks at the stop flag
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.STOPPED;
        }
    }

    private String simulateField(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            JsonNode simulate = node == null ? null : node.get("simulate");
            return simulate == null ? null : simulate.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
