package com.kwiki.graph.persistence;

import java.time.Instant;

/** 恢复和 fencing 所需的图子任务最小持久化投影。 */
public record GraphBuildRunRecord(
        long id,
        long batchId,
        long kbId,
        int chunkIndexVersion,
        String chunkPhysicalIndex,
        long communityIndexVersion,
        String communityPhysicalIndex,
        long graphVersion,
        int mappingSchemaVersion,
        String entityLinkingVersion,
        long contentEpoch,
        long securityEpoch,
        GraphBuildState state,
        GraphBuildStage stage,
        long fencingToken,
        String leaseOwner,
        Instant leaseExpiresAt,
        long eventWatermark) {
}
