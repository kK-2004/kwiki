package com.kwiki.graph.persistence;

/** 清理只允许操作非发布、非构建、无读取租约的快照。 */
public final class GraphSnapshotDeletionGuard {

    private GraphSnapshotDeletionGuard() {
    }

    public static void requireDeletable(GraphSnapshotState state, boolean activePublication,
                                        boolean activeBuild, boolean activeReadLease) {
        if (state == null || state == GraphSnapshotState.DELETING
                || state == GraphSnapshotState.PUBLISHED || activePublication
                || activeBuild || activeReadLease) {
            throw new IllegalStateException("图快照仍被发布、构建或读取引用");
        }
    }
}
