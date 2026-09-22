package com.kwiki.graph.persistence;

import com.kwiki.rag.retrieval.EntityLinkingStatus;

import java.util.List;

/** 发布校验读取的 ES CHILD 实体映射观察值。 */
public record EntityMappingObservation(String sourceChunkId, List<String> entityIds,
                                       String entityLinkingVersion,
                                       EntityLinkingStatus status) {
    public EntityMappingObservation {
        if (sourceChunkId == null || sourceChunkId.isBlank()
                || entityLinkingVersion == null || entityLinkingVersion.isBlank()
                || status == null) {
            throw new IllegalArgumentException("实体映射观察值无效");
        }
        entityIds = entityIds == null ? List.of() : entityIds.stream()
                .filter(java.util.Objects::nonNull).distinct().sorted().toList();
    }
}
