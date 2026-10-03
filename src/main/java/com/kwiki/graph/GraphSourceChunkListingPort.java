package com.kwiki.graph;

import com.kwiki.rag.retrieval.ChunkHit;

import java.util.List;

/** 列出 CHUNK 物理索引中某知识库全部有效 CHILD（按 chunkKey 排序）。 */
public interface GraphSourceChunkListingPort {

    List<ChunkHit> listChildren(String chunkPhysicalIndex, long kbId, int maxResults);
}
