package dev.soup.queue;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All timings and switches, bound from {@code queue.*} (environment variables in compose).
 * Defaults live in application.yml and match design D6, D13 and D16.
 */
@ConfigurationProperties("queue")
public record QueueProperties(
        String mutant,
        Duration lease,
        Duration heartbeatInterval,
        Duration reaperInterval,
        int reaperBatch,
        Duration maxRuntime,
        Duration retryBase,
        Duration stopDeadline,
        Duration idleBackoffMin,
        Duration idleBackoffMax,
        boolean testHooks,
        Emulator emulator) {

    /** Emulated "training": random duration and failure probability (design D13). */
    public record Emulator(Duration minDuration, Duration maxDuration, double failureProbability) {
    }
}
