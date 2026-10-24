package com.kwiki.graph.build;

import com.kwiki.graph.persistence.GraphBuildRunRecord;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.CancellationException;

/** 单个子任务执行上下文：租约身份、取消探针、进度上报与模型调用失败统计。 */
public record RunContext(GraphBuildRunRecord run, String owner, long fencingToken,
                         BooleanSupplier cancelled, Consumer<RunProgress> progress,
                         ModelCallObserver modelCalls) {

    /** 模型调用的单次失败：是否超时、是否还会重试。实现负责累计到子任务上。 */
    @FunctionalInterface
    public interface ModelCallObserver {
        void attemptFailed(boolean timeout, boolean willRetry);
    }

    public RunContext {
        if (run == null || owner == null || owner.isBlank() || fencingToken < 0) {
            throw new IllegalArgumentException("执行上下文身份无效");
        }
        cancelled = cancelled == null ? () -> false : cancelled;
        progress = progress == null ? ignored -> {
        } : progress;
        modelCalls = modelCalls == null ? (timeout, willRetry) -> {
        } : modelCalls;
    }

    public RunContext(GraphBuildRunRecord run, String owner, long fencingToken,
                      BooleanSupplier cancelled, Consumer<RunProgress> progress) {
        this(run, owner, fencingToken, cancelled, progress, null);
    }

    /**
     * 模型调用的重试观察者：记录每次失败尝试（超时按 {@code MODEL_TIMEOUT} 失败码识别），
     * 供抽取与摘要阶段传给 {@link RetryPolicy#run(java.util.function.Supplier, RetryPolicy.AttemptObserver)}。
     */
    public RetryPolicy.AttemptObserver modelAttempts() {
        return (failure, willRetry) -> modelCalls.attemptFailed(
                failure instanceof GraphBuildFailure graph && GraphBuildFailure.MODEL_TIMEOUT.equals(graph.code()),
                willRetry);
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
