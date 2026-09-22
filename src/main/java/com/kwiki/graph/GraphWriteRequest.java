package com.kwiki.graph;

import java.util.List;

/** 图投影批写请求；批次大小和字节限制由实现层统一执行。 */
public record GraphWriteRequest(
        long kbId,
        long graphVersion,
        List<GraphSourceChunk> sources,
        List<GraphEntity> entities,
        List<GraphRelation> relations,
        String runId,
        long fencingToken) {

    public GraphWriteRequest {
        if (kbId <= 0 || graphVersion <= 0 || runId == null || runId.isBlank() || fencingToken < 0) {
            throw new IllegalArgumentException("图批写身份无效");
        }
        sources = sources == null ? List.of() : List.copyOf(sources);
        entities = entities == null ? List.of() : List.copyOf(entities);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }
}
