package com.kwiki.rag.retrieval;

/** 一个被检索到的 CHILD 分块，并带有生成引用所需的溯源信息。 */
public record ChunkHit(
        String chunkKey,
        String parentChunkKey,
        long kbId,
        String resourceType,
        long resourceId,
        Long revisionId,
        String headingPath,
        int charStart,
        int charEnd,
        String content) {
}
