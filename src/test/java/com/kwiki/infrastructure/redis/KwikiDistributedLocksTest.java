package com.kwiki.infrastructure.redis;

import com.kk2004.common.lock.DistributedLock;
import com.kk2004.common.lock.DistributedLockFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 共享锁适配器的契约：锁名命名空间、有界等待（watchdog 变体——
 * 二参 tryLock，无显式租期）、竞争/中断/锁服务不可用时跳过而非无锁
 * 执行、临界区异常仍在 finally 解锁、且只解锁当前线程确实持有的锁。
 */
class KwikiDistributedLocksTest {

    @Test
    void lockNamesFollowTheKwikiNamespace() {
        assertThat(KwikiDistributedLocks.lockName("index-rebuild:v2"))
                .isEqualTo("kwiki:lock:index-rebuild:v2");
        assertThat(KwikiDistributedLocks.lockName("recycle-bin-cleanup"))
                .isEqualTo("kwiki:lock:recycle-bin-cleanup");
    }

    @Test
    void tryRunUsesTheWatchdogVariantAndUnlocksAfterTheSection() throws Exception {
        DistributedLock lock = mock(DistributedLock.class);
        KwikiDistributedLocks locks = adapterOver(factoryReturning(lock));
        when(lock.tryLock(250, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        AtomicBoolean executed = new AtomicBoolean();

        assertThat(locks.tryRun("demo", Duration.ofMillis(250), () -> executed.set(true))).isTrue();

        assertThat(executed).isTrue();
        verify(lock).unlock();
    }

    @Test
    void contentionSkipsTheSectionWithoutUnlocking() throws Exception {
        DistributedLock lock = mock(DistributedLock.class);
        KwikiDistributedLocks locks = adapterOver(factoryReturning(lock));
        when(lock.tryLock(250, TimeUnit.MILLISECONDS)).thenReturn(false);
        AtomicBoolean executed = new AtomicBoolean();

        assertThat(locks.tryRun("demo", Duration.ofMillis(250), () -> executed.set(true))).isFalse();

        assertThat(executed).isFalse();
        verify(lock, never()).unlock();
    }

    @Test
    void interruptionRestoresTheFlagAndSkips() throws Exception {
        DistributedLock lock = mock(DistributedLock.class);
        KwikiDistributedLocks locks = adapterOver(factoryReturning(lock));
        when(lock.tryLock(250, TimeUnit.MILLISECONDS))
                .thenThrow(new InterruptedException("cancelled"));
        AtomicBoolean executed = new AtomicBoolean();

        Thread runner = new Thread(() -> {
            assertThat(locks.tryRun("demo", Duration.ofMillis(250), () -> executed.set(true))).isFalse();
            assertThat(Thread.interrupted()).as("interrupt flag restored").isTrue();
        });
        runner.start();
        runner.join(5_000);

        assertThat(executed).isFalse();
        verify(lock, never()).unlock();
    }

    @Test
    void missingLockFactoryFailsClosedInsteadOfRunningUnprotected() {
        KwikiDistributedLocks locks = new KwikiDistributedLocks(
                com.kwiki.testutil.StandardTestProperties.nullProvider());
        AtomicBoolean executed = new AtomicBoolean();

        assertThat(locks.tryRun("demo", Duration.ZERO, () -> executed.set(true))).isFalse();
        assertThat(locks.acquire("demo", Duration.ZERO)).isNull();
        assertThat(locks.isAvailable()).isFalse();
        assertThat(executed).isFalse();
    }

    @Test
    void sectionFailureStillUnlocksAndPropagates() throws Exception {
        DistributedLock lock = mock(DistributedLock.class);
        KwikiDistributedLocks locks = adapterOver(factoryReturning(lock));
        when(lock.tryLock(250, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThatThrownBy(() -> locks.tryRun("demo", Duration.ofMillis(250), () -> {
            throw new IllegalStateException("business failure");
        })).isInstanceOf(IllegalStateException.class);

        verify(lock).unlock();
    }

    @Test
    void acquiredHandleUnlocksOnlyWhileOwned() throws Exception {
        DistributedLock lock = mock(DistributedLock.class);
        KwikiDistributedLocks locks = adapterOver(factoryReturning(lock));
        when(lock.tryLock(250, TimeUnit.MILLISECONDS)).thenReturn(true);
        // 租约到期后被外部释放：句柄关闭时不得再次解锁。
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        AutoCloseable handle = locks.acquire("demo", Duration.ofMillis(250));
        assertThat(handle).isNotNull();
        handle.close();

        verify(lock, never()).unlock();
    }

    @Test
    void boundedLeaseUsesSdkThreeArgumentLock() throws Exception {
        DistributedLock lock=mock(DistributedLock.class);
        KwikiDistributedLocks locks=adapterOver(factoryReturning(lock));
        when(lock.tryLock(200,30000,TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        AutoCloseable held=locks.acquire("search-index-alias",
                Duration.ofMillis(200),Duration.ofSeconds(30));
        assertThat(held).isNotNull(); held.close();

        verify(lock).tryLock(200,30000,TimeUnit.MILLISECONDS);
        verify(lock).unlock();
    }

    private static KwikiDistributedLocks adapterOver(DistributedLockFactory factory) {
        return new KwikiDistributedLocks(com.kwiki.testutil.StandardTestProperties.providerOf(factory));
    }

    private static DistributedLockFactory factoryReturning(DistributedLock lock) {
        DistributedLockFactory factory = mock(DistributedLockFactory.class);
        when(factory.getDistributedLock(anyString())).thenReturn(lock);
        return factory;
    }
}
