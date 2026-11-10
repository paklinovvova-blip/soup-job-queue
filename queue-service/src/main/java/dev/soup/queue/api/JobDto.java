package dev.soup.queue.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.soup.queue.job.Job;

/**
 * Public representation of a job (spec job-api, "Job representation"). Timestamps are UTC instants
 * taken from the database clock. Internal lease fields are not exposed.
 */
public record JobDto(
        UUID id,
        String status,
        JsonNode payload,
        int attempts,
        int maxAttempts,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt) {

    public static JobDto from(Job job, ObjectMapper mapper) {
        try {
            return new JobDto(job.id(), job.status().db(), mapper.readTree(job.payload()),
                    job.attempts(), job.maxAttempts(), job.lastError(),
                    instant(job.createdAt()), instant(job.updatedAt()),
                    instant(job.startedAt()), instant(job.finishedAt()));
        } catch (Exception e) {
            throw new IllegalStateException("stored payload is not valid JSON for job " + job.id(), e);
        }
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
