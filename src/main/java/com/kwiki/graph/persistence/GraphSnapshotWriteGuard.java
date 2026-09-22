package com.kwiki.graph.persistence;

/** 防止过期 worker 或新任务覆盖 READY/已发布图快照。 */
public final class GraphSnapshotWriteGuard {

    private GraphSnapshotWriteGuard() {
    }

    public static void requireWritable(GraphSnapshotState state,
                                       long expectedRunId, long actualRunId,
                                       long expectedFencingToken, long actualFencingToken) {
        if (state != GraphSnapshotState.BUILDING) {
            throw new IllegalStateException("图快照已冻结，不允许继续写入: " + state);
        }
        if (expectedRunId != actualRunId || expectedFencingToken != actualFencingToken) {
            throw new IllegalStateException("图 worker fencing 身份已失效");
        }
    }
}
