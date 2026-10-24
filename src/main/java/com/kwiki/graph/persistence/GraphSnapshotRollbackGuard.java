package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphResourceId;

import java.util.List;
import java.util.function.Predicate;

/** 回滚门禁：目标必须是完整且与配对 Chunk 版本兼容的历史快照，且不得引用已物理删除的资源。 */
public final class GraphSnapshotRollbackGuard {

    private GraphSnapshotRollbackGuard() {
    }

    public static void requireRollbackable(GraphSnapshotEntry entry, int chunkIndexVersion) {
        requireRollbackable(entry, chunkIndexVersion, List.of(), resource -> true);
    }

    /**
     * 带来源清单的存在性校验：清单中任一资源在业务库中已不存在（物理删除，
     * 连回收站都没有）即拒绝回滚——在线门禁无法再核验其内容，回滚会带回
     * 引用悬空的快照。仅归档、仍可恢复的来源不受限制（查询期计为已退役来源）。
     */
    public static void requireRollbackable(GraphSnapshotEntry entry, int chunkIndexVersion,
                                           List<GraphResourceId> manifestSources,
                                           Predicate<GraphResourceId> resourceExists) {
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
        for (GraphResourceId resource : manifestSources) {
            if (!resourceExists.test(resource)) {
                throw new IllegalStateException(
                        "回滚目标快照引用已物理删除的资源，不能回滚");
            }
        }
    }
}
