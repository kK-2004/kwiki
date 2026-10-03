package com.kwiki.graph.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 全局模型限流器的并发许可与令牌桶行为。 */
class RateLimiterTest {

    @Test
    void 并发许可占满时后续调用阻塞直到许可释放() throws Exception {
        RateLimiter limiter = new RateLimiter(2, 0);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int i = 0; i < 2; i++) {
                final int answer = i;
                pool.submit(() -> limiter.call(() -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return answer;
                }));
            }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> third = CompletableFuture.supplyAsync(
                    () -> limiter.call(() -> 3), pool);
            assertThatThrownBy(() -> third.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(third.get(2, TimeUnit.SECONDS)).isEqualTo(3);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void qps为2时两次调用至少间隔500毫秒() {
        AtomicLong now = new AtomicLong();
        // 注入的睡眠函数直接推进注入时钟，使间隔可以用时钟值断言
        RateLimiter limiter = new RateLimiter(1, 2.0, now::get,
                nanos -> now.addAndGet(nanos));
        List<Long> callTimes = new java.util.ArrayList<>();
        limiter.call(() -> callTimes.add(now.get()));
        limiter.call(() -> callTimes.add(now.get()));
        limiter.call(() -> callTimes.add(now.get()));

        assertThat(callTimes).hasSize(3);
        assertThat(callTimes.get(1) - callTimes.get(0)).isGreaterThanOrEqualTo(500_000_000L);
        assertThat(callTimes.get(2) - callTimes.get(1)).isGreaterThanOrEqualTo(500_000_000L);
        assertThat(limiter.interval()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void 中断等待许可时恢复中断标志并抛出运行时异常() throws Exception {
        RateLimiter limiter = new RateLimiter(1, 0);
        CountDownLatch entered = new CountDownLatch(1);
        Thread holder = new Thread(() -> limiter.call(() -> {
            entered.countDown();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 1;
        }));
        holder.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        Thread waiter = new Thread(() -> assertThatThrownBy(() -> limiter.call(() -> 2))
                .isInstanceOf(IllegalStateException.class));
        waiter.start();
        // 等待者进入 acquire 后再中断
        Thread.sleep(200);
        waiter.interrupt();
        waiter.join(2000);
        holder.interrupt();
        holder.join(2000);
    }
}
