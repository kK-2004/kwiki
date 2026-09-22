package com.kwiki.graph;

/** 从已授权 ES CHILD 元数据提取的图种子，不触发 MENTIONS 反查。 */
public record GraphSeed(String entityId, String sourceChunkId) {
    public GraphSeed {
        if (entityId == null || entityId.isBlank()
                || sourceChunkId == null || sourceChunkId.isBlank()) {
            throw new IllegalArgumentException("图种子身份无效");
        }
    }
}
