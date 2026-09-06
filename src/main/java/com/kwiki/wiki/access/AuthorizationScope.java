package com.kwiki.wiki.access;

import java.util.Map;
import java.util.Set;
import java.util.function.LongUnaryOperator;

/**
 * Immutable authorization scope resolved once per request. Carries the accessible
 * knowledge bases and the per-knowledge-base scope versions captured at resolution
 * time; the outbound guard re-checks those versions before evidence leaves the
 * system, so membership changes during long retrieval/generation abort the request.
 */
public record AuthorizationScope(
        long userId,
        boolean superuser,
        Set<Long> accessibleKbIds,
        Map<Long, Long> kbVersions) {

    public AuthorizationScope {
        accessibleKbIds = Set.copyOf(accessibleKbIds);
        kbVersions = Map.copyOf(kbVersions);
    }

    /** Superusers see everything; everyone else only their member knowledge bases. */
    public boolean includes(long kbId) {
        return superuser || accessibleKbIds.contains(kbId);
    }

    /**
     * True when any tracked knowledge-base version has advanced beyond the value
     * captured in this scope (membership changed since resolution).
     */
    public boolean isStale(LongUnaryOperator currentVersion) {
        for (Map.Entry<Long, Long> entry : kbVersions.entrySet()) {
            if (currentVersion.applyAsLong(entry.getKey()) > entry.getValue()) {
                return true;
            }
        }
        return false;
    }
}
