package com.kwiki.graph.persistence;

/** 按知识库和 Chunk 物理版本维护的单一图快照发布指针。 */
public record GraphPublicationRecord(
        long kbId,
        int chunkIndexVersion,
        Long activeSnapshotId,
        long contentEpoch,
        long securityEpoch) {
}
