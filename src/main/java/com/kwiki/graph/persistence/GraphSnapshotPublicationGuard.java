package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSnapshot;

/** 发布前的不可变快照、版本配对和 epoch 门禁。 */
public final class GraphSnapshotPublicationGuard {

    private GraphSnapshotPublicationGuard() {
    }

    public static void requirePublishable(GraphSnapshot snapshot,
                                          GraphSnapshotState state,
                                          long currentContentEpoch,
                                          long currentSecurityEpoch,
                                          int expectedChunkIndexVersion) {
        if (snapshot == null || state != GraphSnapshotState.READY) {
            throw new IllegalStateException("图快照尚未完成校验");
        }
        if (snapshot.chunkIndexVersion() != expectedChunkIndexVersion) {
            throw new IllegalStateException("图快照与 Chunk 版本不兼容");
        }
        if (snapshot.contentEpoch() != currentContentEpoch
                || snapshot.securityEpoch() != currentSecurityEpoch) {
            throw new IllegalStateException("图快照来源 epoch 已过期");
        }
    }
}
