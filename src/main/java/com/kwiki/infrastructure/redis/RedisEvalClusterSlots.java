package com.kwiki.infrastructure.redis;

import com.kwiki.eval.EvalClusterSlots;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Redis ZSET 上的集群执行名额：{@code kwiki:eval:slots}，member = 实例标识（每实例至多 1 个）、
 * score = 到期毫秒数（以 Redis 服务器时间计）。Lua 原子执行「清除过期 → 未达上限时占位」，实例宕机后占位到期自动释放；
 * 执行期间由心跳续期，续期失败说明名额已被回收（执行资格丢失）。
 */
public class RedisEvalClusterSlots implements EvalClusterSlots {

    static final String KEY = "kwiki:eval:slots";

    /** 清除过期占位后：仍持有则续期；未达 max 则占位；否则失败。ARGV：instanceId、ttl 毫秒、max；时间取 Redis 服务器时间。 */
    private static final RedisScript<Long> TRY_ACQUIRE = new DefaultRedisScript<>(RedisLuaTime.SERVER_NOW + """
            local expiry = now + tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
              redis.call('ZADD', KEYS[1], expiry, ARGV[1])
              return 1
            end
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then return 0 end
            redis.call('ZADD', KEYS[1], expiry, ARGV[1])
            return 1
            """, Long.class);

    /** 仅在仍持有且未过期时续期；占位已过期被回收返回 0（失去执行资格）。ARGV：instanceId、ttl 毫秒。 */
    private static final RedisScript<Long> RENEW = new DefaultRedisScript<>(RedisLuaTime.SERVER_NOW + """
            local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if not score or tonumber(score) <= now then
              redis.call('ZREM', KEYS[1], ARGV[1])
              return 0
            end
            redis.call('ZADD', KEYS[1], now + tonumber(ARGV[2]), ARGV[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisEvalClusterSlots(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String instanceId, int maxConcurrentRuns, Duration ttl) {
        Long ok = redis.execute(TRY_ACQUIRE, List.of(KEY), instanceId, Long.toString(ttl.toMillis()),
                Integer.toString(maxConcurrentRuns));
        return Long.valueOf(1L).equals(ok);
    }

    @Override
    public boolean renew(String instanceId, Duration ttl) {
        Long ok = redis.execute(RENEW, List.of(KEY), instanceId, Long.toString(ttl.toMillis()));
        return Long.valueOf(1L).equals(ok);
    }

    @Override
    public void release(String instanceId) {
        redis.opsForZSet().remove(KEY, instanceId);
    }
}
