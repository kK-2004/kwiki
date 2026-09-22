package com.kwiki.graph;

/** 请求开始时固定的一份快照配对；读取期间不得重新解析别名或活动指针。 */
public record GraphSnapshotPin(GraphSnapshot snapshot, long leaseId) {
    public GraphSnapshotPin {
        if (snapshot == null || leaseId <= 0) {
            throw new IllegalArgumentException("快照读取 pin 无效");
        }
    }
}
