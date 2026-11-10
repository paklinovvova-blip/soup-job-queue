package dev.soup.queue.job;

import java.util.UUID;

/**
 * What a worker gets from a successful claim. {@code leaseToken} is the fencing token of this
 * attempt: every later write for the job must present it.
 */
public record ClaimedJob(UUID id, long leaseToken, int attempts, String payload) {
}
