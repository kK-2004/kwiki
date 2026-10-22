package com.kwiki.eval;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 未启用 Redis 时的进程内 run 执行锁：语义与分布式锁一致（本进程内互斥）。 */
public class InMemoryEvalRunLocks implements EvalRunLocks {

    private final Set<String> held = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public AutoCloseable tryAcquire(String runId) {
        if (!held.add(runId)) return null;
        return () -> held.remove(runId);
    }
}
