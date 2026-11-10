package dev.soup.queue.job;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A row of the {@code jobs} table. {@code payload} is the raw JSON text stored in jsonb. */
public record Job(
        UUID id,
        String idempotencyKey,
        String requestHash,
        String payload,
        JobStatus status,
        int attempts,
        int maxAttempts,
        long leaseToken,
        String leaseOwner,
        OffsetDateTime leaseExpiresAt,
        OffsetDateTime claimedAt,
        OffsetDateTime runAfter,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}
