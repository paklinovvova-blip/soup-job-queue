package dev.soup.queue.api;

import org.springframework.http.HttpStatus;

/** An expected client-facing error. Turned into {@link ApiError} by {@link ApiExceptionHandler}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient JobDto job;
    private final String jobId;

    private ApiException(HttpStatus status, String code, String message, JobDto job, String jobId) {
        super(message);
        this.status = status;
        this.code = code;
        this.job = job;
        this.jobId = jobId;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", message, null, null);
    }

    public static ApiException missingIdempotencyKey() {
        return new ApiException(HttpStatus.BAD_REQUEST, "missing_idempotency_key",
                "Idempotency-Key header is required", null, null);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", message, null, null);
    }

    public static ApiException idempotencyConflict(String jobId) {
        return new ApiException(HttpStatus.CONFLICT, "idempotency_conflict",
                "Idempotency-Key was already used with a different payload", null, jobId);
    }

    public static ApiException jobFinished(JobDto job) {
        return new ApiException(HttpStatus.CONFLICT, "job_finished",
                "Job is already " + job.status() + " and cannot be cancelled", job, null);
    }

    public ApiError toBody() {
        return new ApiError(code, getMessage(), job, jobId);
    }

    public HttpStatus status() {
        return status;
    }
}
