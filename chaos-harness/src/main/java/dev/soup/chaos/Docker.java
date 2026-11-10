package dev.soup.chaos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Thin wrapper over the docker CLI: compose lifecycle and per-container kill / start / pause. */
final class Docker {

    record Result(int exit, String output) {
        boolean ok() {
            return exit == 0;
        }
    }

    private final Map<String, String> env;

    Docker(Map<String, String> env) {
        this.env = env;
    }

    Result run(long timeoutSeconds, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().putAll(env);
            Process p = pb.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, "timeout: " + String.join(" ", cmd));
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return new Result(p.exitValue(), out);
        } catch (IOException e) {
            return new Result(-1, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted");
        }
    }

    Result composeDown() {
        return run(120, "docker", "compose", "down", "-v", "--remove-orphans");
    }

    Result composeUp(int workers) {
        return run(900, "docker", "compose", "up", "-d", "--wait", "--scale", "worker=" + workers);
    }

    /** Full ids of all worker containers, running or not. */
    List<String> workerContainers() {
        Result r = run(30, "docker", "compose", "ps", "-a", "-q", "worker");
        if (!r.ok() || r.output().isBlank()) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList(r.output().split("\\s+")));
    }

    Result kill(String id) {
        return run(20, "docker", "kill", "-s", "KILL", id);
    }

    Result start(String id) {
        return run(30, "docker", "start", id);
    }

    Result pause(String id) {
        return run(20, "docker", "pause", id);
    }

    Result unpause(String id) {
        return run(20, "docker", "unpause", id);
    }

    String state(String id) {
        return run(10, "docker", "inspect", "-f", "{{.State.Status}}", id).output();
    }

    static String shortId(String id) {
        return id.length() > 12 ? id.substring(0, 12) : id;
    }
}
