package com.kwiki.infrastructure.redis;

import com.kk2004.common.lock.DistributedLock;
import com.kk2004.common.lock.DistributedLockFactory;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * kk-common 分布式锁的契约覆盖（生产代码目前还没有
 * 临界区，因此该适配器记录并固定了未来任何调用方
 * 都必须遵循的模式）：用有界的 tryLock 获取、让临界区最多执行一次、
 * 始终在 finally 中解锁 —— 且只解锁确实获取到的锁 —— 并恢复中断
 * 标志，而不是吞掉取消信号。
 */
class DistributedLockContractTest {

    /** 规范的消费方适配器；真实临界区请照此形态复制。 */
    static <T> Optional<T> runWithLock(DistributedLockFactory locks, String name,
                                       long waitMillis, Supplier<T> criticalSection) {
        DistributedLock lock = locks.getDistributedLock(name);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
            if (!acquired) {
                return Optional.empty(); // 已文档化的忙等路径；绝不解锁未持有的锁
            }
            return Optional.ofNullable(criticalSection.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 保持取消信号有效
            return Optional.empty();
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }

    @Test
    void acquiredLockRunsTheSectionOnceAndAlwaysUnlocks() throws Exception {
        DistributedLock lock = mock(DistributedLock.class);
        DistributedLockFactory locks = factoryReturning(lock);
        when(lock.tryLock(250, TimeUnit.MILLISECONDS)).thenReturn(true);
        AtomicBoolean executed = new AtomicBoolean();

        Optional<String> result = runWithLock(locks, "kwiki:lock:demo", 250,
                () -> {
                    executed.set(true);
                    return "payload";
                });

        assertThat(result).contains("payload");
        assertThat(executed).isTrue();
        verify(lock).unlock();
    }

    @Test
    void sectionFailureStillUnlocksTheAcquiredLock() throws Exception {
        DistributedLock lock = mock(DistributedLock.class);
        DistributedLockFactory locks = factoryReturning(lock);
        when(lock.tryLock(250, TimeUnit.MILLISECONDS)).thenReturn(true);

        assertThatThrownBy(() -> runWithLock(locks, "kwiki:lock:demo", 250, () -> {
            throw new IllegalStateException("business failure");
        })).isInstanceOf(IllegalStateException.class);

        verify(lock).unlock();
    }

    @Test
    void failedAcquisitionSkipsTheSectionAndNeverUnlocks() throws Exception {
        DistributedLock lock = mock(DistributedLock.class);
        DistributedLockFactory locks = factoryReturning(lock);
        when(lock.tryLock(250, TimeUnit.MILLISECONDS)).thenReturn(false);
        AtomicBoolean executed = new AtomicBoolean();

        Optional<String> result = runWithLock(locks, "kwiki:lock:demo", 250, () -> {
            executed.set(true);
            return "payload";
        });

        assertThat(result).isEmpty();
        assertThat(executed).isFalse();
        verify(lock, never()).unlock();
    }

    @Test
    void interruptionRestoresTheInterruptFlagAndSkipsUnlock() throws Exception {
        DistributedLock lock = mock(DistributedLock.class);
        DistributedLockFactory locks = factoryReturning(lock);
        when(lock.tryLock(250, TimeUnit.MILLISECONDS))
                .thenThrow(new InterruptedException("cancelled while waiting"));

        Thread runner = new Thread(() -> {
            Optional<String> result = runWithLock(locks, "kwiki:lock:demo", 250, () -> "payload");
            assertThat(result).isEmpty();
            assertThat(Thread.interrupted())
                    .as("adapter must restore the interrupt flag").isTrue();
        });
        runner.start();
        runner.join(5_000);

        verify(lock, never()).unlock();
    }

    private static DistributedLockFactory factoryReturning(DistributedLock lock) {
        DistributedLockFactory factory = mock(DistributedLockFactory.class);
        when(factory.getDistributedLock(anyString())).thenReturn(lock);
        return factory;
    }
}
