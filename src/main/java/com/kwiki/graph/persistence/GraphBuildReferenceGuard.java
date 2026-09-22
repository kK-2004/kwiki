package com.kwiki.graph.persistence;

/** 索引删除/原地重建前检查是否仍被活动图任务固定引用。 */
public final class GraphBuildReferenceGuard {

    private GraphBuildReferenceGuard() {
    }

    public static void requireIndexMutationAllowed(GraphBuildRunRecord activeRun,
                                                   int chunkIndexVersion) {
        if (activeRun != null && activeRun.chunkIndexVersion() == chunkIndexVersion) {
            throw new IllegalStateException("Chunk 版本仍被活动图构建引用");
        }
    }
}
