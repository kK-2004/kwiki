package com.kwiki.graph.model;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 并发许可 + 令牌桶的全局模型限流器：同一模型端点的所有图构建子任务共享。
 * qps ≤ 0 表示不限制调用频率；睡眠函数与时钟可注入以便测试。
 */
public final class RateLimiter {

    private final Semaphore permits;
    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final LongConsumer sleeperNanos;
    private final AtomicLong nextAllowedNanos = new AtomicLong();

    public RateLimiter(int maxConcurrency, double qps) {
        this(maxConcurrency, qps, System::nanoTime, RateLimiter::sleepNanos);
    }

    RateLimiter(int maxConcurrency, double qps, LongSupplier nanoTime, LongConsumer sleeperNanos) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("模型并发上限无效");
        }
        this.permits = new Semaphore(maxConcurrency);
        this.intervalNanos = qps > 0 ? (long) (1_000_000_000L / qps) : 0;
        this.nanoTime = nanoTime;
        this.sleeperNanos = sleeperNanos;
    }

    /** 持有并发许可执行调用；中断时恢复中断标志并抛出运行时异常。 */
    public <T> T call(Supplier<T> body) {
        if (body == null) {
            throw new IllegalArgumentException("限流调用体不能为空");
        }
        try {
            permits.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型调用等待并发许可时被中断", interrupted);
        }
        try {
            awaitRate();
            return body.get();
        } finally {
            permits.release();
        }
    }

    private void awaitRate() {
        if (intervalNanos <= 0) {
            return;
        }
        long now;
        do {
            now = nanoTime.getAsLong();
            long previous = nextAllowedNanos.get();
            long allowed = Math.max(now, previous);
            if (nextAllowedNanos.compareAndSet(previous, allowed + intervalNanos)) {
                long wait = allowed - now;
                if (wait > 0) {
                    sleeperNanos.accept(wait);
                }
                return;
            }
        } while (true);
    }

    private static void sleepNanos(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型调用等待调用频率时被中断", interrupted);
        }
    }

    /** 测试与诊断用：当前配置的调用间隔。 */
    public Duration interval() {
        return Duration.ofNanos(intervalNanos);
    }
}
