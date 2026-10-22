package com.kwiki.eval;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/** 未启用 Redis 时的进程内集群名额：语义与 Redis ZSET + Lua 实现一致。 */
public class InMemoryEvalClusterSlots implements EvalClusterSlots {

    /** instanceId → 名额到期毫秒数。 */
    private final ConcurrentSkipListMap<String, Long> slots = new ConcurrentSkipListMap<>();

    @Override
    public synchronized boolean tryAcquire(String instanceId, int maxConcurrentRuns, Duration ttl) {
        purge(Instant.now());
        Long expiry = slots.get(instanceId);
        if (expiry != null) {
            slots.put(instanceId, Instant.now().plus(ttl).toEpochMilli());
            return true;
        }
        if (slots.size() >= maxConcurrentRuns) return false;
        slots.put(instanceId, Instant.now().plus(ttl).toEpochMilli());
        return true;
    }

    @Override
    public synchronized boolean renew(String instanceId, Duration ttl) {
        purge(Instant.now());
        if (!slots.containsKey(instanceId)) return false;
        slots.put(instanceId, Instant.now().plus(ttl).toEpochMilli());
        return true;
    }

    @Override
    public synchronized void release(String instanceId) {
        slots.remove(instanceId);
    }

    /** 当前持有的实例数（测试观察用）。 */
    public int held() {
        purge(Instant.now());
        return slots.size();
    }

    private void purge(Instant now) {
        Iterator<Map.Entry<String, Long>> iterator = slots.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now.toEpochMilli()) iterator.remove();
        }
    }
}
