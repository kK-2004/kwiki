package com.kwiki.graph;

import java.util.List;

/** 有界图扩展结果；edgeChecks 包含被过滤的边。 */
public record GraphExpansionResult(List<GraphEntity> entities, List<GraphRelation> relations,
                                   int edgeChecks, boolean truncated) {
    public GraphExpansionResult {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relations = relations == null ? List.of() : List.copyOf(relations);
        if (edgeChecks < 0) throw new IllegalArgumentException("边检查数无效");
    }
}
