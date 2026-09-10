package com.kwiki.rag.retrieval;

import java.util.List;

/** 已授权的父分块，并附上其命中的子分块以供引用。 */
public record ParentEvidenceChunk(
        String parentChunkKey,
        long kbId,
        String resourceType,
        long resourceId,
        Long revisionId,
        String headingPath,
        String content,
        double bestRrfScore,
        List<ChunkHit> matchedChildren) {

    public ParentEvidenceChunk {
        matchedChildren = matchedChildren == null ? List.of() : List.copyOf(matchedChildren);
    }
}
