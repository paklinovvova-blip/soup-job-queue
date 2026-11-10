package dev.soup.queue.worker;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dev.soup.queue.QueueProperties;
import dev.soup.queue.job.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Returns jobs with an expired lease (dead worker) or an overrun attempt (hung worker) to the queue.
 * Runs in every process, API included, so expired jobs are reaped even when all workers are down.
 * Several reapers at once are safe: the statement skips locked rows (design D6).
 */
@Component
public class Reaper implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Reaper.class);

    private final JobRepository repo;
    private final QueueProperties props;
    private ScheduledExecutorService timer;

    public Reaper(JobRepository repo, QueueProperties props) {
        this.repo = repo;
        this.props = props;
    }

    /** One pass. Never throws, so a failure never cancels the timer (review finding S2). */
    public int tick() {
        try {
            int reaped = repo.reap(props.maxRuntime(), props.reaperBatch());
            if (reaped > 0) {
                log.info("reaper returned {} job(s) with expired lease or max runtime", reaped);
            }
            return reaped;
        } catch (Throwable t) {
            log.warn("reaper tick failed, will retry: {}", t.toString());
            return -1;
        }
    }

    @Override
    public synchronized void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reaper");
            t.setDaemon(true);
            return t;
        });
        long ms = props.reaperInterval().toMillis();
        timer.scheduleWithFixedDelay(this::tick, ms, ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return timer != null;
    }
}
