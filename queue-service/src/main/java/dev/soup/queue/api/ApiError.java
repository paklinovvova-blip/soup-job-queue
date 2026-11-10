package dev.soup.queue.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of every non-2xx response. {@code job} is set for {@code job_finished},
 * {@code jobId} for {@code idempotency_conflict}; both are omitted otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String message, JobDto job, String jobId) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, null, null);
    }
}
