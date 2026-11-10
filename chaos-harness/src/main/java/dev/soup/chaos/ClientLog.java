package dev.soup.chaos;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Every request the harness sent and what it got back. Source of truth for I1, I5 and I6. */
final class ClientLog {

    /**
     * @param type       create | resend | conflict | cancel
     * @param code       HTTP status, -1 = unknown (timeout or network error)
     * @param concurrent true for creates fired at the same instant as a duplicate group
     */
    record Entry(long tMillis, String type, String key, String jobId, int code, String status,
                 String error, boolean concurrent) {
    }

    private final ConcurrentLinkedQueue<Entry> entries = new ConcurrentLinkedQueue<>();
    private final long start = System.currentTimeMillis();

    void add(String type, String key, Api.Response r, boolean concurrent) {
        entries.add(new Entry(System.currentTimeMillis() - start, type, key, r.jobId(), r.code(),
                r.reportedStatus(), r.text("error"), concurrent));
    }

    void add(Entry e) {
        entries.add(e);
    }

    List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    void writeJsonl(Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (Entry e : entries) {
                w.write(mapper.writeValueAsString(e));
                w.newLine();
            }
        }
    }
}
