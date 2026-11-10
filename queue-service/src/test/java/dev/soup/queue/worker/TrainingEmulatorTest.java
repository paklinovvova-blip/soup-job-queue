package dev.soup.queue.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import dev.soup.queue.QueueProperties;
import org.junit.jupiter.api.Test;

/** Task 4.2. */
class TrainingEmulatorTest {

    private static final QueueProperties.Emulator DEFAULTS =
            new QueueProperties.Emulator(Duration.ofMillis(100), Duration.ofMillis(500), 0.05);

    @Test
    void failureRateIsAboutFivePercentAndDurationsInRange() {
        int failures = 0;
        for (int i = 0; i < 10_000; i++) {
            TrainingEmulator.Plan plan = TrainingEmulator.plan(UUID.randomUUID(), 1 + i % 3, DEFAULTS);
            assertThat(plan.durationMs()).isBetween(100L, 500L);
            if (plan.fails()) {
                failures++;
            }
        }
        assertThat(failures / 10_000.0).isBetween(0.04, 0.06);
    }

    @Test
    void sameAttemptBehavesTheSameOnAnyWorker() {
        UUID id = UUID.randomUUID();
        assertThat(TrainingEmulator.plan(id, 7, DEFAULTS)).isEqualTo(TrainingEmulator.plan(id, 7, DEFAULTS));
    }

    @Test
    void stopFlagEndsTheRunWithinOneSlice() {
        QueueProperties.Emulator longRun = new QueueProperties.Emulator(Duration.ofSeconds(10), Duration.ofSeconds(10), 0);
        TrainingEmulator emulator = new TrainingEmulator(longRun, false, Exiter.SYSTEM);
        Attempt attempt = new Attempt(UUID.randomUUID(), 1, 1, System.nanoTime());
        new Thread(() -> {
            sleep(200);
            attempt.requestStop("test");
        }).start();
        long start = System.nanoTime();
        TrainingEmulator.Result result = emulator.run(attempt.jobId, 1, "{}", attempt);
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(result.outcome()).isEqualTo(TrainingEmulator.Outcome.STOPPED);
        assertThat(tookMs).isLessThan(200 + 2 * TrainingEmulator.SLICE_MS + 50);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
