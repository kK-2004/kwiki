package com.kwiki.graph.persistence;

import java.time.LocalDate;

/** 创建图批次时冻结的范围和双 ES 版本目标。 */
public record GraphBuildBatchCommand(
        String idempotencyKey,
        String scopeKind,
        String requestedKnowledgeBaseIdsJson,
        int chunkIndexVersion,
        String chunkPhysicalIndex,
        long communityIndexVersion,
        boolean autoPublish,
        String requestedBy,
        LocalDate scheduleDate) {

    public GraphBuildBatchCommand {
        require(idempotencyKey, "idempotencyKey");
        if (!"ALL".equals(scopeKind) && !"KNOWLEDGE_BASE".equals(scopeKind)) {
            throw new IllegalArgumentException("scopeKind 无效");
        }
        require(requestedKnowledgeBaseIdsJson, "requestedKnowledgeBaseIdsJson");
        if (chunkIndexVersion < 1 || communityIndexVersion < 1) {
            throw new IllegalArgumentException("版本必须为正数");
        }
        require(chunkPhysicalIndex, "chunkPhysicalIndex");
        require(requestedBy, "requestedBy");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
