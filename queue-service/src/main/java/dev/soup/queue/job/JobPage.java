package dev.soup.queue.job;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** One page of a keyset-paginated listing. {@code next} is null on the last page. */
public record JobPage(List<Job> items, Cursor next) {

    /** Position after which the next page starts: the (created_at, id) of the last item. */
    public record Cursor(OffsetDateTime createdAt, UUID id) {
    }
}
