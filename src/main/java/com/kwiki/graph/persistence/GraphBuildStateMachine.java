package com.kwiki.graph.persistence;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 持久化阶段的显式转换表；恢复和重试不得跳过必要的终态。 */
public final class GraphBuildStateMachine {

    private static final Map<GraphBuildState, Set<GraphBuildState>> ALLOWED = Map.ofEntries(
            Map.entry(GraphBuildState.QUEUED, EnumSet.of(GraphBuildState.EXTRACTING,
                    GraphBuildState.WAITING_FOR_CHUNKS, GraphBuildState.UNSUPPORTED,
                    GraphBuildState.CANCELLED, GraphBuildState.FAILED)),
            Map.entry(GraphBuildState.EXTRACTING, EnumSet.of(GraphBuildState.PROJECTING,
                    GraphBuildState.FAILED, GraphBuildState.CANCELLED, GraphBuildState.STALE)),
            Map.entry(GraphBuildState.PROJECTING, EnumSet.of(GraphBuildState.CLUSTERING,
                    GraphBuildState.FAILED, GraphBuildState.CANCELLED, GraphBuildState.STALE)),
            Map.entry(GraphBuildState.CLUSTERING, EnumSet.of(GraphBuildState.SUMMARIZING,
                    GraphBuildState.FAILED, GraphBuildState.CANCELLED, GraphBuildState.STALE)),
            Map.entry(GraphBuildState.SUMMARIZING, EnumSet.of(GraphBuildState.INDEXING,
                    GraphBuildState.FAILED, GraphBuildState.CANCELLED, GraphBuildState.STALE)),
            Map.entry(GraphBuildState.INDEXING, EnumSet.of(GraphBuildState.VALIDATING,
                    GraphBuildState.FAILED, GraphBuildState.CANCELLED, GraphBuildState.STALE)),
            Map.entry(GraphBuildState.VALIDATING, EnumSet.of(GraphBuildState.READY,
                    GraphBuildState.FAILED, GraphBuildState.CANCELLED, GraphBuildState.STALE)),
            Map.entry(GraphBuildState.READY, EnumSet.of(GraphBuildState.PUBLISHED,
                    GraphBuildState.STALE)),
            Map.entry(GraphBuildState.PUBLISHED, EnumSet.of(GraphBuildState.STALE)),
            Map.entry(GraphBuildState.WAITING_FOR_CHUNKS, EnumSet.of(GraphBuildState.QUEUED,
                    GraphBuildState.FAILED, GraphBuildState.CANCELLED)),
            Map.entry(GraphBuildState.UNSUPPORTED, EnumSet.of(GraphBuildState.QUEUED,
                    GraphBuildState.FAILED, GraphBuildState.CANCELLED)),
            Map.entry(GraphBuildState.FAILED, EnumSet.of(GraphBuildState.QUEUED)),
            Map.entry(GraphBuildState.PARTIAL_FAILED, EnumSet.of(GraphBuildState.QUEUED)),
            Map.entry(GraphBuildState.CANCELLED, EnumSet.of(GraphBuildState.QUEUED)),
            Map.entry(GraphBuildState.STALE, EnumSet.of(GraphBuildState.QUEUED)),
            Map.entry(GraphBuildState.NEEDS_ATTENTION, EnumSet.of(GraphBuildState.QUEUED,
                    GraphBuildState.CANCELLED, GraphBuildState.FAILED)));

    private GraphBuildStateMachine() {
    }

    public static boolean canAdvance(GraphBuildState from, GraphBuildState to) {
        return from != null && to != null && (from == to
                || ALLOWED.getOrDefault(from, Set.of()).contains(to));
    }

    public static void requireAdvance(GraphBuildState from, GraphBuildState to) {
        if (!canAdvance(from, to)) {
            throw new IllegalStateException("图构建状态不可转换: " + from + " -> " + to);
        }
    }
}
