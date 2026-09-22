package com.kwiki.graph;

import java.util.List;

/** 有向语义关系及其来源证据。 */
public record GraphRelation(
        String relationId,
        String sourceEntityId,
        GraphPredicate predicate,
        String targetEntityId,
        double confidence,
        List<GraphSourceRef> sourceRefs) {

    public GraphRelation {
        if (relationId == null || relationId.isBlank()
                || sourceEntityId == null || sourceEntityId.isBlank()
                || targetEntityId == null || targetEntityId.isBlank()) {
            throw new IllegalArgumentException("关系端点不能为空");
        }
        if (predicate == null || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("关系类型或置信度无效");
        }
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        if (sourceRefs.isEmpty()) {
            throw new IllegalArgumentException("关系必须包含来源证据");
        }
    }
}
