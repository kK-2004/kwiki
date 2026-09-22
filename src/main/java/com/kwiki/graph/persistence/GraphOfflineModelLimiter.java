package com.kwiki.graph.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/** 离线摘要/embedding 独立于在线问答的并发、频率和重试预算。 */
@Component
public class GraphOfflineModelLimiter {

    private final Semaphore concurrency;
    private final long intervalNanos;
    private final int maxAttempts;
    private final AtomicLong nextAllowedNanos = new AtomicLong();

    public GraphOfflineModelLimiter(
            @Value("${kwiki.graph.offline.max-concurrency:2}") int maxConcurrency,
            @Value("${kwiki.graph.offline.min-interval:1s}") Duration minInterval,
            @Value("${kwiki.graph.offline.max-attempts:3}") int maxAttempts) {
        if (maxConcurrency < 1 || minInterval.isNegative() || maxAttempts < 1) {
            throw new IllegalArgumentException("离线模型限流配置无效");
        }
        this.concurrency = new Semaphore(maxConcurrency);
        this.intervalNanos = minInterval.toNanos();
        this.maxAttempts = maxAttempts;
    }

    public <T> T execute(Callable<T> operation) {
        if (operation == null) throw new IllegalArgumentException("离线模型操作不能为空");
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            boolean acquired = false;
            try {
                concurrency.acquire();
                acquired = true;
                awaitRate();
                return operation.call();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("离线模型调用被中断", interrupted);
            } catch (Exception failure) {
                last = failure;
            } finally {
                if (acquired) concurrency.release();
            }
        }
        throw new IllegalStateException("离线模型重试耗尽", last);
    }

    private void awaitRate() throws InterruptedException {
        long now;
        do {
            now = System.nanoTime();
            long previous = nextAllowedNanos.get();
            long allowed = Math.max(now, previous);
            if (nextAllowedNanos.compareAndSet(previous, allowed + intervalNanos)) {
                long wait = allowed - now;
                if (wait > 0) {
                    long millis = wait / 1_000_000;
                    int nanos = (int) (wait % 1_000_000);
                    Thread.sleep(millis, nanos);
                }
                return;
            }
        } while (true);
    }
}
