package com.kwiki.graph;

/** 快照 epoch 与当前权威值的比较；不匹配时摘要与图增强按库禁用，不伪装可用。 */
public final class GraphSnapshotEpochGuard {

    private GraphSnapshotEpochGuard() {
    }

    public static boolean summariesUsable(GraphSnapshot snapshot, long currentContentEpoch,
                                          long currentSecurityEpoch) {
        return snapshot != null
                && snapshot.contentEpoch() == currentContentEpoch
                && snapshot.securityEpoch() == currentSecurityEpoch;
    }
}
