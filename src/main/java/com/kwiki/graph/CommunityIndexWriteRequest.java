package com.kwiki.graph;

import java.util.List;

/** 社区摘要写入 COMMUNITY 物理索引时冻结的身份和模型配置。 */
public record CommunityIndexWriteRequest(
        String physicalIndex,
        long kbId,
        long graphVersion,
        long communityIndexVersion,
        String communityId,
        String sourceManifestId,
        long sourceContentEpoch,
        long sourceSecurityEpoch,
        int sourceChunkIndexVersion,
        List<String> representativeEntityIds,
        int entityCount,
        String summaryModel,
        String summaryPromptVersion,
        String embeddingModel,
        int embeddingDimensions,
        CommunitySummary summary) {

    public CommunityIndexWriteRequest {
        if (physicalIndex == null || physicalIndex.isBlank() || kbId <= 0 || graphVersion <= 0
                || communityIndexVersion <= 0 || communityId == null || communityId.isBlank()
                || sourceManifestId == null || sourceManifestId.isBlank()
                || sourceContentEpoch < 0 || sourceSecurityEpoch < 0
                || sourceChunkIndexVersion < 1 || entityCount < 0
                || summaryModel == null || summaryModel.isBlank()
                || summaryPromptVersion == null || summaryPromptVersion.isBlank()
                || embeddingModel == null || embeddingModel.isBlank()
                || embeddingDimensions < 1 || summary == null) {
            throw new IllegalArgumentException("社区索引写入身份或摘要配置无效");
        }
        representativeEntityIds = representativeEntityIds == null
                ? List.of() : representativeEntityIds.stream().distinct().sorted().toList();
    }

    public String communityKey() {
        return "kb_" + kbId + ":" + graphVersion + ":" + communityId;
    }
}
