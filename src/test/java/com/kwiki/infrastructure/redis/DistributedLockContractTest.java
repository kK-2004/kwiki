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
 * Contract coverage for the kk-common distributed lock (production code has no
 * critical section yet, so this adapter documents and pins the pattern any future
 * caller must follow): acquire with a bounded tryLock, run the section at most once,
 * always unlock in finally — and only what was acquired — and restore the interrupt
 * flag instead of swallowing cancellation.
 */
class DistributedLockContractTest {

    /** Canonical consumer adapter; copy this shape for real critical sections. */
    static <T> Optional<T> runWithLock(DistributedLockFactory locks, String name,
                                       long waitMillis, Supplier<T> criticalSection) {
        DistributedLock lock = locks.getDistributedLock(name);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
            if (!acquired) {
                return Optional.empty(); // documented busy path; never unlock a lock not held
            }
            return Optional.ofNullable(criticalSection.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // keep the cancel signal alive
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
