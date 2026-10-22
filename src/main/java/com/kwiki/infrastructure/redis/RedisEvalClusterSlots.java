package com.kwiki.infrastructure.redis;

import com.kwiki.eval.EvalClusterSlots;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Redis ZSET 上的集群执行名额：{@code kwiki:eval:slots}，member = 实例标识（每实例至多 1 个）、
 * score = 到期毫秒数。Lua 原子执行「清除过期 → 未达上限时占位」，实例宕机后占位到期自动释放；
 * 执行期间由心跳续期，续期失败说明名额已被回收（执行资格丢失）。
 */
public class RedisEvalClusterSlots implements EvalClusterSlots {

    static final String KEY = "kwiki:eval:slots";

    /** 清除过期占位后：仍持有则续期；未达 max 则占位；否则失败。ARGV：now、instanceId、到期、max。 */
    private static final RedisScript<Long> TRY_ACQUIRE = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            if redis.call('ZSCORE', KEYS[1], ARGV[2]) then
              redis.call('ZADD', KEYS[1], ARGV[3], ARGV[2])
              return 1
            end
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[4]) then return 0 end
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[2])
            return 1
            """, Long.class);

    /** 仅在仍持有且未过期时续期；占位已过期被回收返回 0（失去执行资格）。ARGV：instanceId、now、到期。 */
    private static final RedisScript<Long> RENEW = new DefaultRedisScript<>("""
            local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if not score or tonumber(score) <= tonumber(ARGV[2]) then
              redis.call('ZREM', KEYS[1], ARGV[1])
              return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisEvalClusterSlots(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String instanceId, int maxConcurrentRuns, Duration ttl) {
        long now = System.currentTimeMillis();
        Long ok = redis.execute(TRY_ACQUIRE, List.of(KEY), Long.toString(now), instanceId,
                Long.toString(now + ttl.toMillis()), Integer.toString(maxConcurrentRuns));
        return Long.valueOf(1L).equals(ok);
    }

    @Override
    public boolean renew(String instanceId, Duration ttl) {
        long now = System.currentTimeMillis();
        Long ok = redis.execute(RENEW, List.of(KEY), instanceId, Long.toString(now),
                Long.toString(now + ttl.toMillis()));
        return Long.valueOf(1L).equals(ok);
    }

    @Override
    public void release(String instanceId) {
        redis.opsForZSet().remove(KEY, instanceId);
    }
}
