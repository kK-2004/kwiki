package com.kwiki.infrastructure.redis;

import com.kk2004.common.lock.DistributedLock;
import com.kk2004.common.lock.DistributedLockFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * kk-common {@link DistributedLockFactory} 之上的全项目共享薄适配器：
 * 锁名统一为 {@code kwiki:lock:<purpose>[:<resource>]}，等待有界，
 * 获取失败按调用方语义返回 BUSY/跳过，中断时恢复中断标志，
 * 只有当前线程确实持有时才在关闭句柄时解锁。
 *
 * <p>租约策略：临界区使用 SDK 的 watchdog 变体（tryLock(wait) 不带
 * 显式租期，Redisson 自动续期），供批处理/ES 删除等长临界区使用；
 * 需要有界租期的调用方应在 SDK 锁上补充显式租期重载后接入。
 *
 * <p>失败语义：锁工厂不可用（SDK 显式关闭或未装配）时获取失败——
 * 依赖互斥的写操作/调度轮次失败关闭或跳过，绝不无锁执行。
 */
@Component
public class KwikiDistributedLocks {

    private static final String NAME_PREFIX = "kwiki:lock:";

    private final DistributedLockFactory factory;

    public KwikiDistributedLocks(ObjectProvider<DistributedLockFactory> factory) {
        this.factory = factory.getIfAvailable();
    }

    /** 规范锁名：kwiki:lock:&lt;purpose&gt;[:&lt;resource&gt;]。 */
    public static String lockName(String purposeWithResource) {
        return NAME_PREFIX + purposeWithResource;
    }

    public boolean isAvailable() {
        return factory != null;
    }

    /**
     * 单实例调度语义：在锁保护下执行 {@code body}；未获取锁（超时、
     * 中断、锁服务不可用）时跳过本轮并返回 false，不执行 body。
     */
    public boolean tryRun(String purposeWithResource, Duration wait, Runnable body) {
        AutoCloseable held = acquire(purposeWithResource, wait);
        if (held == null) {
            return false;
        }
        try {
            body.run();
            return true;
        } finally {
            closeQuietly(held);
        }
    }

    /** 锁保护下的有返回值执行；未获取锁返回 null，调用方按 BUSY/跳过处理。 */
    public <T> T tryRunReturning(String purposeWithResource, Duration wait,
                                 java.util.function.Supplier<T> body) {
        AutoCloseable held = acquire(purposeWithResource, wait);
        if (held == null) {
            return null;
        }
        try {
            return body.get();
        } finally {
            closeQuietly(held);
        }
    }

    /**
     * 显式临界区：返回的句柄必须在 finally 中关闭（仅当前线程持有
     * 时解锁）。返回 null 表示未获取，调用方按 BUSY/跳过处理。
     */
    public AutoCloseable acquire(String purposeWithResource, Duration wait) {
        return acquireInternal(purposeWithResource, wait, null);
    }

    /** 短临界区使用显式有界租期；到期后 ownership 检查阻止误解锁。 */
    public AutoCloseable acquire(String purposeWithResource, Duration wait, Duration lease) {
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return acquireInternal(purposeWithResource, wait, lease);
    }

    private AutoCloseable acquireInternal(String purposeWithResource, Duration wait,
                                          Duration lease) {
        if (factory == null) {
            return null;
        }
        DistributedLock lock = factory.getDistributedLock(lockName(purposeWithResource));
        boolean acquired;
        try {
            acquired = lease == null
                    ? lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS)
                    : lock.tryLock(wait.toMillis(), lease.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!acquired) {
            return null;
        }
        return () -> {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        };
    }

    private static void closeQuietly(AutoCloseable handle) {
        try {
            handle.close();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception ignored) {
            // AutoCloseable 签名要求；解锁路径自身不会抛受检异常。
        }
    }
}
