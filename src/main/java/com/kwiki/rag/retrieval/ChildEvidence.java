package com.kwiki.rag.retrieval;

import java.util.Optional;

/**
 * 单次检索调用经过双分支召回与 RRF 融合后保留的子分块。
 * 分支排名为 1 起始，未命中该分支时为 null；
 * 两个分支都命中是正常混合检索情形，RRF
 * 分数为命中该分块的各分支排名之和。
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

    /** 稳定的展示顺序：融合分数降序，其次按 chunkKey。 */
    public static int orderByScore(ChildEvidence a, ChildEvidence b) {
        int byScore = Double.compare(b.rrfScore(), a.rrfScore());
        return byScore != 0 ? byScore : a.chunkKey().compareTo(b.chunkKey());
    }
}
