package com.kwiki.eval;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 集群执行名额：全集群同时执行的 run 总数上限。每实例至多占 1 个名额，
 * 占位带到期时间，执行期间续期，实例宕机后到期自动释放。
 */
public interface EvalClusterSlots {

    /** 尝试占用一个名额（已在持有则续期）；达到 max 时返回 false。 */
    boolean tryAcquire(String instanceId, int maxConcurrentRuns, Duration ttl);

    /** 续期本实例名额；名额已过期被回收（失去执行资格）返回 false。 */
    boolean renew(String instanceId, Duration ttl);

    /** 释放本实例名额。 */
    void release(String instanceId);
}
