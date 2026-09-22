package com.kwiki.graph.persistence;

import java.time.Instant;

/** 快照拥有的待清理资源清单行。 */
public record GraphResourceReferenceRecord(long id, String resourceKind,
                                           String resourceIdentity,
                                           String cleanupState,
                                           Instant deletedAt) {
    public GraphResourceReferenceRecord {
        if (id <= 0 || resourceKind == null || resourceKind.isBlank()
                || resourceIdentity == null || resourceIdentity.isBlank()
                || cleanupState == null || cleanupState.isBlank()) {
            throw new IllegalArgumentException("快照资源引用无效");
        }
    }

    public boolean deleted() {
        return "DELETED".equalsIgnoreCase(cleanupState);
    }
}
