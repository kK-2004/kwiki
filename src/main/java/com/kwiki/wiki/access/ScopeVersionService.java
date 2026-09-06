package com.kwiki.wiki.access;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic per-knowledge-base scope versions backed by the scope_version table.
 * Membership changes bump the version, which makes in-flight requests stale so the
 * outbound guard can abort them. When no JDBC template is available (unit/dev
 * context) an in-memory fallback keeps the service usable; production always runs
 * with MySQL.
 */
@Service
public class ScopeVersionService {

    private static final long INITIAL_VERSION = 1L;

    private final JdbcOperations jdbc;
    private final Map<Long, AtomicLong> inMemoryFallback = new ConcurrentHashMap<>();

    public ScopeVersionService(ObjectProvider<JdbcOperations> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    /** Current version of a knowledge base (1 before the first membership change). */
    public long current(long kbId) {
        if (jdbc == null) {
            return inMemory(kbId).get();
        }
        try {
            Long version = jdbc.queryForObject(
                    "SELECT version FROM scope_version WHERE kb_id = ?", Long.class, kbId);
            return version == null ? INITIAL_VERSION : version;
        } catch (EmptyResultDataAccessException e) {
            return INITIAL_VERSION;
        }
    }

    /**
     * Atomically advances the version. Guarded-update + insert-retry keeps the value
     * strictly monotonic under concurrent membership changes.
     */
    public long bump(long kbId) {
        if (jdbc == null) {
            return inMemory(kbId).incrementAndGet();
        }
        while (true) {
            long observed = current(kbId);
            int updated = jdbc.update(
                    "UPDATE scope_version SET version = version + 1 WHERE kb_id = ? AND version = ?",
                    kbId, observed);
            if (updated > 0) {
                return observed + 1;
            }
            try {
                jdbc.update("INSERT INTO scope_version (kb_id, version) VALUES (?, ?)",
                        kbId, INITIAL_VERSION);
            } catch (DuplicateKeyException raceLost) {
                // another writer created the row first; retry the guarded update
            }
        }
    }

    private AtomicLong inMemory(long kbId) {
        return inMemoryFallback.computeIfAbsent(kbId, k -> new AtomicLong(INITIAL_VERSION));
    }
}
