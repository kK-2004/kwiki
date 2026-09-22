package com.kwiki.graph.persistence;

import java.time.Instant;

/** 独立图抽取派生目标的租约投影。 */
public record GraphExtractionTargetRecord(
        long id,
        long extractionId,
        String targetKind,
        String targetIdentity,
        int attempts,
        int maxAttempts,
        String leaseOwner,
        Instant leaseExpiresAt) {
}
