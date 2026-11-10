package dev.soup.queue.worker;

import java.net.InetAddress;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Worker id = {@code <hostname>-<8 hex>}. In Docker the hostname is the container id, so the
 * harness can map a lease owner to a container, and the random suffix differs on every process start.
 */
public final class WorkerIdentity {

    private WorkerIdentity() {
    }

    public static String newWorkerId() {
        return hostname() + "-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
    }

    static String hostname() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }
}
