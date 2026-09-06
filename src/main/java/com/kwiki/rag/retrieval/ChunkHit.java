package com.kwiki.rag.retrieval;

/** One retrieved CHILD chunk with the provenance needed for citations. */
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
