package dev.soup.queue.api;

import java.net.URI;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP contract from spec job-api. Thin: all decisions are in {@link JobService}. */
@RestController
@Profile("api")
@RequestMapping("/jobs")
public class JobController {

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    public record CreateJobRequest(JsonNode payload) {
    }

    @PostMapping
    public ResponseEntity<JobDto> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) CreateJobRequest body) {
        JobService.CreateResult result = service.create(key, body == null ? null : body.payload());
        if (result.created()) {
            return ResponseEntity.created(URI.create("/jobs/" + result.job().id())).body(result.job());
        }
        return ResponseEntity.ok(result.job());
    }

    @GetMapping("/{id}")
    public JobDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    public JobService.ListResult list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.list(status, limit, cursor);
    }

    @PostMapping("/{id}/cancel")
    public JobDto cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }
}
