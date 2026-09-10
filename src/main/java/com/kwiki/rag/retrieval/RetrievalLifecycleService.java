package com.kwiki.rag.retrieval;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Authoritative lifecycle exclusions for every retrieval path. The archived
 * id sets come from the database (the authoritative store); a short-lived
 * in-memory snapshot only accelerates repeated queries inside one window.
 * When the snapshot cannot be built the service fails closed — a query must
 * never run on an untrustworthy filter, and a stale cache must never widen
 * visibility. The sets stay bounded because the daily cleanup physically
 * removes archives older than 7 days; an unexpectedly large set is treated as
 * untrustworthy and rejected.
 */
@Component
public class RetrievalLifecycleService {

    /** Hard cap on the archived-id sets before the filter is rejected. */
    public static final int MAX_TRACKED_ARCHIVED = 10_000;

    public static final class LifecycleFilterException extends RuntimeException {
        public LifecycleFilterException(String message) {
            super(message);
        }
    }

    public record Exclusions(Set<Long> archivedKbIds, Set<Long> archivedPageIds,
                             Instant loadedAt) {
        public boolean isEmpty() {
            return archivedKbIds.isEmpty() && archivedPageIds.isEmpty();
        }
    }

    private final JdbcOperations jdbc;
    private final Duration ttl;
    private final AtomicReference<Exclusions> snapshot = new AtomicReference<>();

    public RetrievalLifecycleService(ObjectProvider<JdbcOperations> jdbc,
                                     @Value("${kwiki.retrieval.lifecycle-filter-ttl:5s}")
                                     Duration ttl) {
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
        this.ttl = ttl;
    }

    /**
     * Current exclusions, reloaded when the snapshot expired. Throws
     * {@link LifecycleFilterException} (fail closed) when the authoritative
     * sets cannot be loaded or are implausibly large.
     */
    public Exclusions exclusions() {
        Exclusions current = snapshot.get();
        Instant now = Instant.now();
        if (current != null && current.loadedAt().plus(ttl).isAfter(now)) {
            return current;
        }
        if (jdbc == null) {
            // Offline/unit context: nothing archived is knowable, so nothing
            // needs excluding; production always runs with MySQL.
            Exclusions empty = new Exclusions(Set.of(), Set.of(), now);
            snapshot.set(empty);
            return empty;
        }
        try {
            List<Long> kbIds = jdbc.query(
                    "SELECT id FROM knowledge_base WHERE status = 'ARCHIVED'",
                    (rs, row) -> rs.getLong(1));
            List<Long> pageIds = jdbc.query(
                    "SELECT id FROM wiki_page WHERE status = 'ARCHIVED'",
                    (rs, row) -> rs.getLong(1));
            if (kbIds.size() > MAX_TRACKED_ARCHIVED || pageIds.size() > MAX_TRACKED_ARCHIVED) {
                throw new LifecycleFilterException(
                        "archived set too large to build a trusted filter (kb="
                                + kbIds.size() + ", page=" + pageIds.size() + ")");
            }
            Exclusions loaded = new Exclusions(Set.copyOf(kbIds), Set.copyOf(pageIds), now);
            snapshot.set(loaded);
            return loaded;
        } catch (LifecycleFilterException e) {
            throw e;
        } catch (Exception e) {
            throw new LifecycleFilterException("lifecycle filter unavailable: " + e.getMessage());
        }
    }

    /** Test hook to pin a deterministic snapshot. */
    public void pinForTest(Exclusions pinned) {
        snapshot.set(pinned);
    }
}
