package dev.soup.chaos;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP client for the queue API. A request that times out (15 s) or fails on the network gets
 * code -1, logged as "unknown": neither success nor failure (spec: "Client log and request timeouts").
 */
final class Api {

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Response(int code, JsonNode body, long latencyNanos) {
        boolean unknown() {
            return code < 0;
        }

        String text(String field) {
            JsonNode n = body == null ? null : body.get(field);
            return n == null || n.isNull() ? null : n.asText();
        }

        /** Status reported by the response: the job's status, or job.status of a job_finished error. */
        String reportedStatus() {
            if (body == null) {
                return null;
            }
            if (body.has("status")) {
                return body.get("status").asText();
            }
            JsonNode job = body.get("job");
            return job == null ? null : job.get("status").asText();
        }

        String jobId() {
            if (body == null) {
                return null;
            }
            if (body.has("id")) {
                return body.get("id").asText();
            }
            if (body.has("jobId")) {
                return body.get("jobId").asText();
            }
            JsonNode job = body.get("job");
            return job == null ? null : job.get("id").asText();
        }
    }

    private final String base;
    private final HttpClient http;

    Api(String base) {
        this.base = base;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    Response create(String key, String payloadJson) {
        return send(HttpRequest.newBuilder(URI.create(base + "/jobs"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString("{\"payload\":" + payloadJson + "}")));
    }

    Response cancel(String id) {
        return send(HttpRequest.newBuilder(URI.create(base + "/jobs/" + id + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    Response get(String id) {
        return send(HttpRequest.newBuilder(URI.create(base + "/jobs/" + id)).GET());
    }

    Response list(String query) {
        return send(HttpRequest.newBuilder(URI.create(base + "/jobs" + query)).GET());
    }

    boolean healthy() {
        return send(HttpRequest.newBuilder(URI.create(base + "/actuator/health")).GET()).code() == 200;
    }

    private Response send(HttpRequest.Builder builder) {
        long start = System.nanoTime();
        try {
            HttpResponse<String> r = http.send(builder.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = r.body() == null || r.body().isBlank() ? null : MAPPER.readTree(r.body());
            return new Response(r.statusCode(), body, System.nanoTime() - start);
        } catch (IOException e) {
            return new Response(-1, null, System.nanoTime() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(-1, null, System.nanoTime() - start);
        }
    }
}
