package dev.soup.queue.api;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.soup.queue.job.Job;
import dev.soup.queue.job.JobPage;
import dev.soup.queue.job.JobRepository;
import dev.soup.queue.job.JobStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** API use cases on top of {@link JobRepository}. Holds the idempotency and cancel decisions. */
@Service
@Profile("api")
public class JobService {

    static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    static final int MAX_KEY_LENGTH = 200;

    private final JobRepository repo;
    private final ObjectMapper mapper;

    public JobService(JobRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public record CreateResult(JobDto job, boolean created) {
    }

    /**
     * Insert-or-return. The unique key decides the race, the hash decides between "repeat" (200)
     * and "conflict" (409). See spec job-api and design D9.
     */
    public CreateResult create(String key, JsonNode payload) {
        validateKey(key);
        if (payload == null || !payload.isObject()) {
            throw ApiException.badRequest("body must be {\"payload\": <JSON object>}");
        }
        String payloadJson = serialize(payload);
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw ApiException.badRequest("payload is larger than 16 KiB");
        }
        String hash = CanonicalJson.hash(payload);

        Optional<Job> inserted = repo.insertIfAbsent(UUID.randomUUID(), key, hash, payloadJson);
        if (inserted.isPresent()) {
            return new CreateResult(dto(inserted.get()), true);
        }
        // The key exists and is committed: ON CONFLICT waited for the other insert to finish.
        Job existing = repo.findByKey(key)
                .orElseThrow(() -> new IllegalStateException("key conflict but no row for key " + key));
        if (!existing.requestHash().equals(hash)) {
            throw ApiException.idempotencyConflict(existing.id().toString());
        }
        return new CreateResult(dto(existing), false);
    }

    public JobDto get(UUID id) {
        return repo.findById(id).map(this::dto)
                .orElseThrow(() -> ApiException.notFound("job " + id + " not found"));
    }

    public record ListResult(java.util.List<JobDto> items, String nextCursor) {
    }

    public ListResult list(String status, Integer limit, String cursor) {
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw ApiException.badRequest("limit must be between 1 and 100");
        }
        JobStatus filter = null;
        if (status != null) {
            try {
                filter = JobStatus.fromDb(status);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("unknown status " + status);
            }
        }
        JobPage page = repo.list(filter, decodeCursor(cursor), size);
        return new ListResult(page.items().stream().map(this::dto).toList(), encodeCursor(page.next()));
    }

    /**
     * Cancel is immediate for queued and running jobs (design D8). If nothing changed,
     * the current state tells which answer to give.
     */
    public JobDto cancel(UUID id) {
        Optional<Job> cancelled = repo.cancel(id);
        if (cancelled.isPresent()) {
            return dto(cancelled.get());
        }
        Job job = repo.findById(id).orElseThrow(() -> ApiException.notFound("job " + id + " not found"));
        if (job.status() == JobStatus.CANCELLED) {
            return dto(job); // cancelling twice is fine
        }
        throw ApiException.jobFinished(dto(job)); // succeeded or failed: terminal, never changes again
    }

    // ----------------------------------------------------------------- helpers

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw ApiException.missingIdempotencyKey();
        }
        if (key.length() > MAX_KEY_LENGTH || !key.chars().allMatch(c -> c >= 0x20 && c <= 0x7e)) {
            throw ApiException.badRequest("Idempotency-Key must be 1 to 200 printable ASCII characters");
        }
    }

    private String serialize(JsonNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("payload is not serializable");
        }
    }

    private JobDto dto(Job job) {
        return JobDto.from(job, mapper);
    }

    /** Cursor = base64url("createdAt|id") of the last item. Opaque to clients. */
    static String encodeCursor(JobPage.Cursor c) {
        if (c == null) {
            return null;
        }
        String raw = c.createdAt().toString() + "|" + c.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static JobPage.Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.indexOf('|');
            return new JobPage.Cursor(OffsetDateTime.parse(raw.substring(0, sep)), UUID.fromString(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid cursor");
        }
    }
}
