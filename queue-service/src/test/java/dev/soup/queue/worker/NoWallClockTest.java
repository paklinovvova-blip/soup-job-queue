package dev.soup.queue.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Task 4.7. Lease, reaper, heartbeat and backoff decisions must use only the database clock
 * (spec job-execution, "Database time is the only clock"). This test fails as soon as someone
 * reads the wall clock in that code. {@code System.nanoTime} is allowed: it is monotonic and is only
 * used for local self-fencing and the watchdog.
 */
class NoWallClockTest {

    private static final Pattern WALL_CLOCK = Pattern.compile(
            "Instant\\.now\\(|System\\.currentTimeMillis\\(|LocalDateTime\\.now\\(|OffsetDateTime\\.now\\(|ZonedDateTime\\.now\\(|Clock\\.system");

    /** The only allowed reads: the startup log line that proves a clock skew is real, and the mutant. */
    private static final Set<String> ALLOWED = Set.of("WorkerRunner.java", "WorkerClockLeaseMutant.java");

    @Test
    void leaseAndScheduleCodeNeverReadsTheWallClock() throws IOException {
        Path root = Path.of("src/main/java/dev/soup/queue");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !ALLOWED.contains(p.getFileName().toString()))
                    .filter(p -> WALL_CLOCK.matcher(read(p)).find())
                    .map(Path::toString)
                    .toList();
        }
        assertThat(offenders).as("files reading the wall clock").isEmpty();
    }

    @Test
    void theCheckWouldCatchAWallClockRead() {
        assertThat(WALL_CLOCK.matcher("var expiry = Instant.now().plus(lease);").find()).isTrue();
        assertThat(WALL_CLOCK.matcher("long t = System.nanoTime();").find()).isFalse();
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
