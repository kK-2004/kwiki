package com.kwiki.rag.retrieval;

import java.util.List;

/** Authorized parent chunk with its matched children attached for citation. */
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
