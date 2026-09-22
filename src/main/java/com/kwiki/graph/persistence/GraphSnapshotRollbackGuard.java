package com.kwiki.graph.persistence;

/** 回滚门禁：目标必须是完整且与配对 Chunk 版本兼容的历史快照。 */
public final class GraphSnapshotRollbackGuard {

    private GraphSnapshotRollbackGuard() {
    }

    public static void requireRollbackable(GraphSnapshotEntry entry, int chunkIndexVersion) {
        if (entry == null) {
            throw new IllegalStateException("回滚目标快照不存在");
        }
        GraphSnapshotState state = entry.state();
        if (state != GraphSnapshotState.READY
                && state != GraphSnapshotState.PUBLISHED
                && state != GraphSnapshotState.RETIRED) {
            throw new IllegalStateException("回滚目标快照不完整: " + state);
        }
        if (entry.snapshot().chunkIndexVersion() != chunkIndexVersion) {
            throw new IllegalStateException("回滚目标快照与 Chunk 版本不兼容");
        }
    }
}
