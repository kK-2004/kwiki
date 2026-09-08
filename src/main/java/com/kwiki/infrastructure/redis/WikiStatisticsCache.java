package com.kwiki.infrastructure.redis;

import com.kk2004.common.lock.DistributedLock;
import com.kk2004.common.lock.DistributedLockFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Best-effort document counters; MySQL remains the source of truth. */
@Component
public class WikiStatisticsCache {
    private static final String PREFIX = "kwiki:wiki:stats:v1:";
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final DefaultRedisScript<Long> VERSIONED_DELTA = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('HGET', KEYS[1], 'dbVersion') or '0')
            local incoming = tonumber(ARGV[1])
            if incoming <= current then return 0 end
            if current == 0 and incoming ~= 1 then return -1 end
            if current > 0 and incoming ~= current + 1 then return -1 end
            if redis.call('HEXISTS', KEYS[1], 'likes') == 0 or redis.call('HEXISTS', KEYS[1], 'favorites') == 0 or redis.call('HEXISTS', KEYS[1], 'comments') == 0 then return -1 end
            local likes = tonumber(redis.call('HINCRBY', KEYS[1], 'likes', ARGV[2]))
            local favorites = tonumber(redis.call('HINCRBY', KEYS[1], 'favorites', ARGV[3]))
            local comments = tonumber(redis.call('HINCRBY', KEYS[1], 'comments', ARGV[4]))
            if likes < 0 or favorites < 0 or comments < 0 then return -1 end
            redis.call('HSET', KEYS[1], 'dbVersion', incoming)
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final JdbcOperations jdbc;
    private final DistributedLockFactory lockFactory;

    public WikiStatisticsCache(ObjectProvider<StringRedisTemplate> redis) {
        this(redis, null, null);
    }

    public WikiStatisticsCache(ObjectProvider<StringRedisTemplate> redis,
                               ObjectProvider<JdbcOperations> jdbc) {
        this(redis, jdbc, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WikiStatisticsCache(ObjectProvider<StringRedisTemplate> redis,
                               ObjectProvider<JdbcOperations> jdbc,
                               ObjectProvider<DistributedLockFactory> lockFactory) {
        this.redis = redis.getIfAvailable();
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
        this.lockFactory = lockFactory == null ? null : lockFactory.getIfAvailable();
    }

    public Counters getOrLoad(long pageId, Supplier<Counters> loader) {
        if (redis == null) return loader.get();
        String key = PREFIX + pageId;
        try {
            Map<Object, Object> values = redis.opsForHash().entries(key);
            Counters cached = parse(values);
            if (cached != null) return cached;
            if (lockFactory != null) {
                DistributedLock lock = lockFactory.getDistributedLock(key + ":lock");
                boolean acquired;
                try { acquired = lock.tryLock(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); acquired = false; }
                if (acquired) {
                    try {
                        values = redis.opsForHash().entries(key);
                        cached = parse(values);
                        if (cached != null) return cached;
                        Counters loaded = loader.get();
                        write(key, loaded);
                        return loaded;
                    } finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
                }
                return loader.get();
            }
            String lockKey = key + ":lock";
            String owner = UUID.randomUUID().toString();
            Boolean locked = redis.opsForValue().setIfAbsent(lockKey, owner, Duration.ofSeconds(5));
            if (Boolean.TRUE.equals(locked)) {
                try {
                    values = redis.opsForHash().entries(key);
                    cached = parse(values);
                    if (cached != null) return cached;
                    Counters loaded = loader.get();
                    write(key, loaded);
                    return loaded;
                } finally {
                    Object current = redis.opsForValue().get(lockKey);
                    if (owner.equals(String.valueOf(current))) redis.delete(lockKey);
                }
            }
            return loader.get();
        } catch (RuntimeException failure) { return loader.get(); }
    }

    public void increment(long pageId, long likes, long favorites, long comments) {
        if (redis == null) return;
        String key = PREFIX + pageId;
        try {
            boolean applied = withStatsLock(pageId, () -> {
                if (!Boolean.TRUE.equals(redis.hasKey(key))) {
                    requestRepair(pageId, "cache_missing_before_delta");
                    return;
                }
                if (likes != 0) redis.opsForHash().increment(key, "likes", likes);
                if (favorites != 0) redis.opsForHash().increment(key, "favorites", favorites);
                if (comments != 0) redis.opsForHash().increment(key, "comments", comments);
                // A zero delta is still a deliberate touch when the row exists.
                redis.expire(key, TTL);
            });
            if (!applied) requestRepair(pageId, "stats_lock_unavailable");
        } catch (RuntimeException ignored) {
            requestRepair(pageId, "redis_update_failed");
        }
    }

    /** Applies a delta only after the surrounding business transaction commits. */
    public void incrementAfterCommit(long pageId, long likes, long favorites, long comments) {
        incrementAfterCommit(pageId, likes, favorites, comments, -1L);
    }

    /** Version-aware after-commit delta. A jump or stale version is repaired from MySQL. */
    public void incrementAfterCommit(long pageId, long likes, long favorites, long comments, long dbVersion) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            if (dbVersion > 0) incrementVersioned(pageId, likes, favorites, comments, dbVersion);
            else increment(pageId, likes, favorites, comments);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                if (dbVersion > 0) incrementVersioned(pageId, likes, favorites, comments, dbVersion);
                else increment(pageId, likes, favorites, comments);
            }
        });
    }

    public void invalidate(long pageId) {
        if (redis == null) return;
        try {
            if (!withStatsLock(pageId, () -> redis.delete(PREFIX + pageId))) {
                requestRepair(pageId, "stats_lock_unavailable");
            }
        } catch (RuntimeException ignored) { }
    }

    public void invalidateAfterCommit(long pageId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidate(pageId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { invalidate(pageId); }
        });
    }

    public void replace(long pageId, Counters counters) {
        if (redis == null || counters == null) return;
        try {
            if (!withStatsLock(pageId, () -> write(PREFIX + pageId, counters))) {
                requestRepair(pageId, "stats_lock_unavailable");
            }
        } catch (RuntimeException ignored) { }
    }

    private void requestRepair(long pageId, String reason) {
        if (jdbc == null) return;
        try {
            jdbc.update("INSERT INTO stats_repair (page_id, reason) VALUES (?, ?)", pageId, reason);
        } catch (RuntimeException ignored) {
            // Redis failure must not turn a committed interaction into a failed API call.
        }
    }

    private void write(String key, Counters value) {
        redis.opsForHash().putAll(key, Map.of("likes", String.valueOf(value.likes()),
                "favorites", String.valueOf(value.favorites()), "comments", String.valueOf(value.comments()),
                "dbVersion", String.valueOf(value.dbVersion())));
        redis.expire(key, TTL);
    }

    private Counters parse(Map<Object, Object> values) {
        if (values == null || values.size() < 3) return null;
        try { return new Counters(Long.parseLong(String.valueOf(values.get("likes"))), Long.parseLong(String.valueOf(values.get("favorites"))), Long.parseLong(String.valueOf(values.get("comments"))), values.get("dbVersion") == null ? 0L : Long.parseLong(String.valueOf(values.get("dbVersion")))); }
        catch (RuntimeException ignored) { return null; }
    }

    private void incrementVersioned(long pageId, long likes, long favorites, long comments, long dbVersion) {
        if (redis == null) return;
        String key = PREFIX + pageId;
        try {
            boolean applied = withStatsLock(pageId, () -> {
                Long result = redis.execute(VERSIONED_DELTA, List.of(key), String.valueOf(dbVersion), String.valueOf(likes), String.valueOf(favorites), String.valueOf(comments), String.valueOf(TTL.toSeconds()));
                if (result == null || result < 0) {
                    requestRepair(pageId, "redis_version_gap");
                    redis.delete(key);
                }
            });
            if (!applied) requestRepair(pageId, "stats_lock_unavailable");
        } catch (RuntimeException failure) {
            requestRepair(pageId, "redis_versioned_update_failed");
            try { redis.delete(key); } catch (RuntimeException ignored) { }
        }
    }

    /** Writes, repair replacement and invalidation share the read-miss lock. */
    private boolean withStatsLock(long pageId, Runnable action) {
        if (lockFactory == null) { action.run(); return true; }
        DistributedLock lock = lockFactory.getDistributedLock(PREFIX + pageId + ":lock");
        boolean acquired;
        try { acquired = lock.tryLock(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        if (!acquired) return false;
        try { action.run(); return true; }
        finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
    }

    public record Counters(long likes, long favorites, long comments, long dbVersion) {
        public Counters(long likes, long favorites, long comments) { this(likes, favorites, comments, 0L); }
    }
}
