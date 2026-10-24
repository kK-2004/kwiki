package com.kwiki.graph.build;

import java.time.Duration;
import java.util.function.Supplier;

/** 阶段内重试：可重试失败按 baseDelay×2^n 退避（上限 30s）；耗尽抛最后一次；永久失败直接抛。 */
public record RetryPolicy(int maxAttempts, Duration baseDelay) {

    private static final long MAX_DELAY_NANOS = 30_000_000_000L;

    public RetryPolicy {
        if (maxAttempts < 1 || baseDelay == null || baseDelay.isNegative()) {
            throw new IllegalArgumentException("重试策略无效");
        }
    }

    public static RetryPolicy of(int maxAttempts, Duration baseDelay) {
        return new RetryPolicy(maxAttempts, baseDelay);
    }

    /** 单次尝试失败的观察者：willRetry 为 true 表示还会再试（取消不回调）。 */
    @FunctionalInterface
    public interface AttemptObserver {
        void attemptFailed(RuntimeException failure, boolean willRetry);
    }

    public <T> T run(Supplier<T> body) {
        return run(body, null);
    }

    public <T> T run(Supplier<T> body, AttemptObserver observer) {
        if (body == null) {
            throw new IllegalArgumentException("重试执行体不能为空");
        }
        Exception last = null;
        long delayNanos = baseDelay.toNanos();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return body.get();
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw cancelled;
            } catch (GraphBuildFailure failure) {
                if (!failure.retryable()) {
                    notify(observer, failure, false);
                    throw failure;
                }
                last = failure;
                notify(observer, failure, attempt < maxAttempts);
            } catch (RuntimeException unexpected) {
                // 未知运行时异常按可重试处理（多为瞬时基础设施故障）
                last = unexpected;
                notify(observer, unexpected, attempt < maxAttempts);
            }
            if (attempt < maxAttempts) {
                sleep(delayNanos);
                delayNanos = Math.min(delayNanos * 2, MAX_DELAY_NANOS);
            }
        }
        throw last instanceof RuntimeException runtime ? runtime : new IllegalStateException(last);
    }

    private static void notify(AttemptObserver observer, RuntimeException failure, boolean willRetry) {
        if (observer != null) {
            observer.attemptFailed(failure, willRetry);
        }
    }

    private static void sleep(long nanos) {
        if (nanos <= 0) {
            return;
        }
        try {
            java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("图构建重试等待被中断", interrupted);
        }
    }
}
