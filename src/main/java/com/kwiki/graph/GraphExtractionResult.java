package com.kwiki.graph;

import java.util.List;

/** 已通过结构校验、等待消歧和持久化的抽取结果。 */
public record GraphExtractionResult(
        GraphSourceChunk sourceChunk,
        List<GraphEntity> entities,
        List<GraphRelation> relations,
        String extractorVersion,
        String promptVersion,
        String entityLinkingVersion) {

    public GraphExtractionResult {
        if (sourceChunk == null) {
            throw new IllegalArgumentException("抽取来源不能为空");
        }
        entities = entities == null ? List.of() : List.copyOf(entities);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }
}
