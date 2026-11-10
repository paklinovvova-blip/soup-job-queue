package dev.soup.queue.worker;

import java.time.Instant;
import java.time.OffsetDateTime;

import dev.soup.queue.QueueProperties;
import dev.soup.queue.job.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Starts the worker loop in the {@code worker} role: waits until the api role has migrated the
 * database, logs the worker id and both clocks, then runs {@link Worker#runLoop()} on its own thread.
 */
@Component
@Profile("worker")
public class WorkerRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);

    private final JdbcTemplate jdbc;
    private final Worker worker;
    private final QueueProperties props;
    private Thread thread;

    public WorkerRunner(JdbcTemplate jdbc, JobRepository repo, QueueProperties props) {
        this.jdbc = jdbc;
        this.props = props;
        TrainingEmulator emulator = new TrainingEmulator(props.emulator(), props.testHooks(), Exiter.SYSTEM);
        this.worker = new Worker(WorkerIdentity.newWorkerId(), repo, emulator, props, Exiter.SYSTEM);
    }

    @Override
    public synchronized void start() {
        thread = new Thread(() -> {
            waitForSchema();
            logStartup();
            worker.runLoop();
        }, "worker-loop");
        thread.start();
    }

    @Override
    public synchronized void stop() {
        worker.requestShutdown();
        if (thread != null) {
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return thread != null;
    }

    private void waitForSchema() {
        while (true) {
            try {
                Integer applied = jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
                if (applied != null && applied > 0) {
                    return;
                }
            } catch (RuntimeException e) {
                log.info("waiting for the api role to migrate the database: {}", e.getMessage());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Wall clock next to database clock: lets the clock-skew run prove that the skew is real.
     * This is the only place the worker reads its wall clock, and only for this log line.
     */
    private void logStartup() {
        OffsetDateTime db = jdbc.queryForObject("SELECT now()", OffsetDateTime.class);
        log.info("worker {} started: mutant={} test-hooks={} lease={} heartbeat={} wall-clock={} db-clock={}",
                worker.workerId(), props.mutant(), props.testHooks(), props.lease(), props.heartbeatInterval(),
                Instant.now(), db);
    }
}
