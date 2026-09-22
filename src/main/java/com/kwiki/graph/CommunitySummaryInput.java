package com.kwiki.graph;

import java.util.List;

/** 社区摘要模型的有界、已筛选输入。 */
public record CommunitySummaryInput(
        long kbId,
        long graphVersion,
        String communityId,
        List<GraphEntity> representativeEntities,
        List<GraphRelation> representativeRelations,
        List<GraphSourceChunk> representativeSources,
        String content,
        String promptVersion) {

    public CommunitySummaryInput {
        if (kbId <= 0 || graphVersion <= 0 || communityId == null || communityId.isBlank()) {
            throw new IllegalArgumentException("社区摘要身份无效");
        }
        representativeEntities = representativeEntities == null ? List.of() : List.copyOf(representativeEntities);
        representativeRelations = representativeRelations == null ? List.of() : List.copyOf(representativeRelations);
        representativeSources = representativeSources == null ? List.of() : List.copyOf(representativeSources);
        content = content == null ? "" : content;
        if (promptVersion == null || promptVersion.isBlank()) {
            throw new IllegalArgumentException("摘要 prompt 版本不能为空");
        }
    }
}
