package dev.soup.queue.job;

import java.util.Locale;

/** queued → running → succeeded | failed | cancelled. The last three are terminal. */
public enum JobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    public String db() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public static JobStatus fromDb(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
