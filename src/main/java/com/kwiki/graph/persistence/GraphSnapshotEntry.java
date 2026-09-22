package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSnapshot;

/** 发布指针当前指向的快照及其状态。 */
public record GraphSnapshotEntry(GraphSnapshot snapshot, GraphSnapshotState state) {
    public GraphSnapshotEntry {
        if (snapshot == null || state == null) {
            throw new IllegalArgumentException("快照记录无效");
        }
    }

    /** 是否仍接受新的请求级 pin；DELETING/RETIRED/FAILED 快照拒绝。 */
    public boolean pinnable() {
        return state == GraphSnapshotState.READY || state == GraphSnapshotState.PUBLISHED;
    }
}
