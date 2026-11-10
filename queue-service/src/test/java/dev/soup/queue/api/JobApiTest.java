package dev.soup.queue.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.soup.queue.job.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Tasks 3.2–3.6: the job-api spec scenarios against the real app and a real Postgres. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class JobApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JobRepository repo;
    @Autowired
    ObjectMapper mapper;
    @LocalServerPort
    int port;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE job_transitions, effects, executions, jobs");
    }

    private ResultActions create(String key, String body) throws Exception {
        var req = post("/jobs").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    private String idOf(ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    // ------------------------------------------------------------------ create

    @Test
    void firstCreateReturns201WithLocationAndQueuedJob() throws Exception {
        ResultActions r = create("k1", "{\"payload\":{\"model\":\"resnet\"}}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/jobs/")))
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.maxAttempts").value(3))
                .andExpect(jsonPath("$.payload.model").value("resnet"))
                .andExpect(jsonPath("$.startedAt").value(nullValue()))
                .andExpect(jsonPath("$.finishedAt").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        assertThat(r.andReturn().getResponse().getHeader("Location")).isEqualTo("/jobs/" + idOf(r));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingOrEmptyKeyIsRejected() throws Exception {
        create(null, "{\"payload\":{}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_idempotency_key"));
        create("", "{\"payload\":{}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_idempotency_key"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isZero();
    }

    @Test
    void invalidKeyOrBodyIsRejected() throws Exception {
        create("x".repeat(201), "{\"payload\":{}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        create("k", "{\"payload\":[1,2]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        create("k", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        create("k", "{not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        create("k", "{\"payload\":{\"blob\":\"" + "a".repeat(16 * 1024) + "\"}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isZero();
    }

    @Test
    void repeatWithSameBodyReturns200AndSameJob() throws Exception {
        String first = idOf(create("k", "{\"payload\":{\"a\":1,\"b\":2}}").andExpect(status().isCreated()));
        create("k", "{\"payload\":{\"a\":1,\"b\":2}}").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(first));
        create("k", "{\"payload\":{\"b\":2,\"a\":1.0}}").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(first));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isEqualTo(1);
    }

    @Test
    void repeatAfterJobFinishedReturnsFinishedJobAndQueuesNothing() throws Exception {
        String id = idOf(create("k", "{\"payload\":{}}"));
        jdbc.update("UPDATE jobs SET status = 'succeeded' WHERE id = ?::uuid", id);
        create("k", "{\"payload\":{}}").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("succeeded"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = 'queued'", Integer.class)).isZero();
    }

    @Test
    void sameKeyDifferentBodyIs409WithExistingJobId() throws Exception {
        String id = idOf(create("k", "{\"payload\":{\"lr\":1}}"));
        create("k", "{\"payload\":{\"lr\":2}}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency_conflict"))
                .andExpect(jsonPath("$.jobId").value(id));
        mvc.perform(get("/jobs/" + id)).andExpect(jsonPath("$.payload.lr").value(1));
    }

    @Test
    void fiftyConcurrentDuplicatesCreateOneJob() throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/jobs"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "same-key")
                .POST(HttpRequest.BodyPublishers.ofString("{\"payload\":{\"x\":1}}"))
                .build();
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return http.send(req, HttpResponse.BodyHandlers.ofString());
            }));
        }
        go.countDown();
        int created = 0;
        int ok = 0;
        Set<String> ids = new HashSet<>();
        for (Future<HttpResponse<String>> f : futures) {
            HttpResponse<String> resp = f.get();
            if (resp.statusCode() == 201) {
                created++;
            } else if (resp.statusCode() == 200) {
                ok++;
            }
            ids.add(mapper.readTree(resp.body()).get("id").asText());
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(ok).isEqualTo(49);
        assertThat(ids).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isEqualTo(1);
    }

    // --------------------------------------------------------------------- get

    @Test
    void getReturnsJobOr404Or400() throws Exception {
        String id = idOf(create("k", "{\"payload\":{}}"));
        mvc.perform(get("/jobs/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/jobs/00000000-0000-0000-0000-000000000000")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mvc.perform(get("/jobs/not-a-uuid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void unknownPathUsesErrorFormat() throws Exception {
        mvc.perform(get("/nope")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
    }

    // -------------------------------------------------------------------- list

    @Test
    void listWalksAllPagesWithoutDuplicatesWhileInserting() throws Exception {
        Set<String> created = new HashSet<>();
        for (int i = 0; i < 45; i++) {
            created.add(idOf(create("k" + i, "{\"payload\":{\"i\":" + i + "}}")));
        }
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            String url = "/jobs?limit=20" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = mapper.readTree(mvc.perform(get(url)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            for (JsonNode item : page.get("items")) {
                assertThat(seen.add(item.get("id").asText())).as("no duplicates").isTrue();
            }
            assertThat(page.has("nextCursor")).isTrue();
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            pages++;
            create("during" + pages, "{\"payload\":{}}"); // inserts while paging
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsAll(created);
    }

    @Test
    void listFiltersByStatusAndValidatesParameters() throws Exception {
        String id = idOf(create("a", "{\"payload\":{}}"));
        create("b", "{\"payload\":{}}");
        jdbc.update("UPDATE jobs SET status = 'failed' WHERE id = ?::uuid", id);
        mvc.perform(get("/jobs?status=failed")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].status").value("failed"))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        for (String bad : List.of("limit=0", "limit=101", "status=bogus", "cursor=%%%", "cursor=bm9wZQ", "limit=abc")) {
            mvc.perform(get("/jobs?" + bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"));
        }
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void cancelQueuedTwiceIsOk() throws Exception {
        String id = idOf(create("k", "{\"payload\":{}}"));
        mvc.perform(post("/jobs/" + id + "/cancel")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());
        mvc.perform(post("/jobs/" + id + "/cancel")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
        assertThat(repo.claim("w", Duration.ofSeconds(5))).isEmpty(); // never executed afterwards
    }

    @Test
    void cancelRunningRevokesTheLease() throws Exception {
        String id = idOf(create("k", "{\"payload\":{}}"));
        var claimed = repo.claim("w", Duration.ofSeconds(5)).orElseThrow();
        long exec = repo.insertExecution(claimed.id(), claimed.leaseToken(), "w");

        mvc.perform(post("/jobs/" + id + "/cancel")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));

        assertThat(repo.heartbeat(claimed.id(), claimed.leaseToken(), Duration.ofSeconds(5), Duration.ofSeconds(60)))
                .isFalse();
        assertThat(repo.complete(claimed.id(), claimed.leaseToken(), "w", exec)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM effects", Integer.class)).isZero();
    }

    @Test
    void cancelFinishedIs409WithJobAndUnknownIs404() throws Exception {
        String id = idOf(create("k", "{\"payload\":{}}"));
        jdbc.update("UPDATE jobs SET status = 'succeeded' WHERE id = ?::uuid", id);
        mvc.perform(post("/jobs/" + id + "/cancel")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("job_finished"))
                .andExpect(jsonPath("$.job.id").value(id))
                .andExpect(jsonPath("$.job.status").value("succeeded"));
        assertThat(jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?::uuid", String.class, id))
                .isEqualTo("succeeded");
        mvc.perform(post("/jobs/00000000-0000-0000-0000-000000000000/cancel")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }
}
