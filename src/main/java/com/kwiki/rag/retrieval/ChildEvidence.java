package com.kwiki.rag.retrieval;

import java.util.Optional;

/**
 * A retained child chunk after the two-branch recall and RRF fusion of one
 * retrieval call. Branch ranks are 1-based and null when the chunk was not hit
 * by that branch; both being present is the normal hybrid case and the RRF
 * score is the sum over the branches that ranked the chunk.
 */
public record ChildEvidence(
        String chunkKey,
        String parentChunkKey,
        long kbId,
        String resourceType,
        long resourceId,
        Long revisionId,
        String headingPath,
        int charStart,
        int charEnd,
        String content,
        Integer bm25Rank,
        Integer vectorRank,
        double rrfScore) {

    public ChildEvidence {
        content = content == null ? "" : content;
    }

    public Optional<Integer> bm25RankOrNull() {
        return Optional.ofNullable(bm25Rank);
    }

    public Optional<Integer> vectorRankOrNull() {
        return Optional.ofNullable(vectorRank);
    }

    public static ChildEvidence fromHit(ChunkHit hit, Integer bm25Rank, Integer vectorRank, double rrfScore) {
        return new ChildEvidence(
                hit.chunkKey(),
                hit.parentChunkKey(),
                hit.kbId(),
                hit.resourceType(),
                hit.resourceId(),
                hit.revisionId(),
                hit.headingPath(),
                hit.charStart(),
                hit.charEnd(),
                hit.content(),
                bm25Rank,
                vectorRank,
                rrfScore);
    }

    /** Stable display order: fused score desc, then chunkKey. */
    public static int orderByScore(ChildEvidence a, ChildEvidence b) {
        int byScore = Double.compare(b.rrfScore(), a.rrfScore());
        return byScore != 0 ? byScore : a.chunkKey().compareTo(b.chunkKey());
    }
}
