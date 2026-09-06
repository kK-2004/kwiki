package com.kwiki.rag.answer;

import com.kwiki.rag.retrieval.ChunkHit;

import java.util.List;

/**
 * One bounded parent body in the generation context. Truncation may shorten the
 * body but NEVER removes matched-child citation identity — children stay attached
 * even when their parent text was cut.
 */
public record ParentEvidence(
        String parentChunkKey,
        String resourceType,
        long resourceId,
        Long revisionId,
        long kbId,
        String headingPath,
        String body,
        boolean truncated,
        int firstHitOrder,
        List<ChunkHit> matchedChildren) {

    public ParentEvidence {
        matchedChildren = matchedChildren == null ? List.of() : List.copyOf(matchedChildren);
    }
}
