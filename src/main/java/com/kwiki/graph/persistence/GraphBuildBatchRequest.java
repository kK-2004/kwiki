package com.kwiki.graph.persistence;

import java.time.LocalDate;
import java.util.List;

/** 管理端或调度器提交图构建批次的请求；知识库集合在此刻冻结。 */
public record GraphBuildBatchRequest(
        String idempotencyKey,
        String scopeKind,
        List<Long> knowledgeBaseIds,
        int chunkIndexVersion,
        String chunkPhysicalIndex,
        int mappingSchemaVersion,
        long configRevision,
        String entityLinkingVersion,
        boolean autoPublish,
        String requestedBy,
        LocalDate scheduleDate) {

    public GraphBuildBatchRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || (!"ALL".equals(scopeKind) && !"KNOWLEDGE_BASE".equals(scopeKind))
                || knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()
                || chunkIndexVersion < 1 || mappingSchemaVersion < 1 || configRevision < 1
                || entityLinkingVersion == null || entityLinkingVersion.isBlank()
                || requestedBy == null || requestedBy.isBlank()
                || chunkPhysicalIndex == null || chunkPhysicalIndex.isBlank()) {
            throw new IllegalArgumentException("图构建批次请求无效");
        }
        if ("KNOWLEDGE_BASE".equals(scopeKind) && knowledgeBaseIds.size() != 1) {
            throw new IllegalArgumentException("指定知识库范围必须只有一个知识库");
        }
        knowledgeBaseIds = knowledgeBaseIds.stream().distinct().sorted().toList();
        if (knowledgeBaseIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("知识库 ID 无效");
        }
    }

    public String knowledgeBaseIdsJson() {
        return "[" + knowledgeBaseIds.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }
}
