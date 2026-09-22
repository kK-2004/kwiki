package com.kwiki.graph.persistence;

import java.time.Duration;

/** 统一执行 lease/fencing/checkpoint，旧 worker 无法推进权威状态。 */
public final class GraphBuildRunCoordinator {

    private final GraphBuildRepository repository;

    public GraphBuildRunCoordinator(GraphBuildRepository repository) {
        this.repository = repository;
    }

    public long recover(long kbId, String owner, Duration leaseDuration) {
        GraphBuildRunRecord run = repository.findActiveRunForUpdate(kbId)
                .orElseThrow(() -> new IllegalStateException("没有可恢复的活动图构建"));
        if (!repository.acquireLease(run.id(), owner, leaseDuration, run.fencingToken())) {
            throw new IllegalStateException("图构建租约已被其他 worker 接管");
        }
        return run.fencingToken() + 1;
    }

    public void checkpoint(GraphBuildRunRecord run, String owner, long fencingToken,
                           GraphBuildState nextState, GraphBuildStage nextStage,
                           long eventWatermark) {
        GraphBuildStateMachine.requireAdvance(run.state(), nextState);
        if (!repository.checkpoint(run.id(), owner, fencingToken,
                nextState, nextStage, eventWatermark)) {
            throw new IllegalStateException("图构建 fencing 已失效");
        }
    }
}
