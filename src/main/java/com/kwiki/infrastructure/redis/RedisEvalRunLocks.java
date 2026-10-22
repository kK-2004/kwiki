package com.kwiki.infrastructure.redis;

import com.kwiki.eval.EvalRunLocks;
import java.time.Duration;

/**
 * run 执行锁的 Redis 实现：kk-common 分布式锁（watchdog 自动续期），
 * 锁名 {@code kwiki:lock:eval-run:{runId}}。锁服务未装配时 {@link #isAvailable()}
 * 为 false，调用方按单实例语义跳过加锁（写入令牌仍然生效）。
 * 由 {@link EvalCoordinationConfiguration} 装配（锁服务可用时才使用本实现）。
 */
public class RedisEvalRunLocks implements EvalRunLocks {

    private final KwikiDistributedLocks locks;

    public RedisEvalRunLocks(KwikiDistributedLocks locks) {
        this.locks = locks;
    }

    @Override
    public boolean isAvailable() {
        return locks.isAvailable();
    }

    @Override
    public AutoCloseable tryAcquire(String runId) {
        return locks.acquire("eval-run:" + runId, Duration.ZERO);
    }
}
