package com.kwiki.graph.build;

import com.kwiki.graph.persistence.GraphBuildRunRecord;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.CancellationException;

/** 单个子任务执行上下文：租约身份、取消探针与进度上报。 */
public record RunContext(GraphBuildRunRecord run, String owner, long fencingToken,
                         BooleanSupplier cancelled, Consumer<RunProgress> progress) {

    public RunContext {
        if (run == null || owner == null || owner.isBlank() || fencingToken < 0) {
            throw new IllegalArgumentException("执行上下文身份无效");
        }
        cancelled = cancelled == null ? () -> false : cancelled;
        progress = progress == null ? ignored -> {
        } : progress;
    }

    /** 取消 → 抛出 CancellationException，执行器据此退出且不写终态。 */
    public void throwIfCancelled() {
        if (cancelled.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("图构建子任务已被取消");
        }
    }

    public void progress(RunProgress value) {
        progress.accept(value);
    }
}
