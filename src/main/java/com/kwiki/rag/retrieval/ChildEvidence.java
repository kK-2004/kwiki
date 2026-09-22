package com.kwiki.rag.retrieval;

import java.util.Optional;
import java.util.List;

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
        double rrfScore,
        String sourceChunkId,
        List<String> entityIds,
        String entityLinkingVersion,
        EntityLinkingStatus entityLinkingStatus) {

    public ChildEvidence {
        content = content == null ? "" : content;
        entityIds = entityIds == null ? List.of() : entityIds.stream()
                .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        entityLinkingStatus = entityLinkingStatus == null
                ? EntityLinkingStatus.MISSING : entityLinkingStatus;
    }

    /** 兼容旧的融合结果构造方式。 */
    public ChildEvidence(String chunkKey, String parentChunkKey, long kbId,
                         String resourceType, long resourceId, Long revisionId,
                         String headingPath, int charStart, int charEnd, String content,
                         Integer bm25Rank, Integer vectorRank, double rrfScore) {
        this(chunkKey, parentChunkKey, kbId, resourceType, resourceId, revisionId,
                headingPath, charStart, charEnd, content, bm25Rank, vectorRank, rrfScore,
                null, List.of(), null, EntityLinkingStatus.MISSING);
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
                rrfScore,
                hit.sourceChunkId(),
                hit.entityIds(),
                hit.entityLinkingVersion(),
                hit.entityLinkingStatus());
    }

    /** 稳定的展示顺序：融合分数降序，其次按 chunkKey。 */
    public static int orderByScore(ChildEvidence a, ChildEvidence b) {
        int byScore = Double.compare(b.rrfScore(), a.rrfScore());
        return byScore != 0 ? byScore : a.chunkKey().compareTo(b.chunkKey());
    }

    /** 图列表与 Chunk 融合列表合并后仅更新融合分数，保留原分支排名与来源身份。 */
    public ChildEvidence withRrfScore(double updatedScore) {
        return new ChildEvidence(chunkKey, parentChunkKey, kbId, resourceType, resourceId,
                revisionId, headingPath, charStart, charEnd, content, bm25Rank, vectorRank,
                updatedScore, sourceChunkId, entityIds, entityLinkingVersion,
                entityLinkingStatus);
    }
}
