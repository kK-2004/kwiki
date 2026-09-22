package com.kwiki.graph.persistence;

import java.util.List;

/** 父批次聚合规则：单库失败不掩盖，全部完成才显示全局成功。 */
public final class GraphBuildAggregate {

    private GraphBuildAggregate() {
    }

    public static GraphBuildState aggregate(List<GraphBuildState> childStates) {
        if (childStates == null || childStates.isEmpty()) {
            return GraphBuildState.FAILED;
        }
        boolean anyFailed = childStates.stream().anyMatch(state -> state == GraphBuildState.FAILED
                || state == GraphBuildState.CANCELLED || state == GraphBuildState.STALE);
        boolean anyReady = childStates.stream().anyMatch(state -> state == GraphBuildState.READY
                || state == GraphBuildState.PUBLISHED);
        boolean allReady = childStates.stream().allMatch(state -> state == GraphBuildState.READY
                || state == GraphBuildState.PUBLISHED);
        if (allReady) return GraphBuildState.READY;
        if (anyFailed) return anyReady ? GraphBuildState.PARTIAL_FAILED : GraphBuildState.FAILED;
        return GraphBuildState.QUEUED;
    }
}
