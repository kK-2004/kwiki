package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSourceChunk;

/** 从已发布且已完成 CHILD 索引的来源创建持久化抽取任务所需的身份。 */
public record GraphExtractionJobSpec(
        GraphSourceChunk sourceChunk,
        String extractorVersion,
        String promptVersion,
        String entityLinkingVersion,
        long contentEpoch,
        long securityEpoch) {

    public GraphExtractionJobSpec {
        if (sourceChunk == null || extractorVersion == null || extractorVersion.isBlank()
                || promptVersion == null || promptVersion.isBlank()
                || entityLinkingVersion == null || entityLinkingVersion.isBlank()
                || contentEpoch < 0 || securityEpoch < 0) {
            throw new IllegalArgumentException("图抽取任务身份无效");
        }
    }
}
