package com.kwiki.graph;

import com.kwiki.rag.retrieval.ChunkHit;

import java.util.Optional;

/** 图关系来源 sourceChunkId → 当前有效 CHILD 的解析端口；由 ES 适配器实现。 */
public interface GraphSourceChildResolverPort {

    /**
     * 在固定的 Chunk 物理索引中按 sourceChunkId 解析当前有效 CHILD；
     * 缺失、生命周期失效或版本不匹配返回 empty。
     */
    Optional<ChunkHit> resolve(String chunkPhysicalIndex, String sourceChunkId,
                               String expectedEntityLinkingVersion, long kbId);
}
