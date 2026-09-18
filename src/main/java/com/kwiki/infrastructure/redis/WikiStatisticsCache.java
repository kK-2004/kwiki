package com.kwiki.infrastructure.redis;

import com.kk2004.common.lock.DistributedLock;
import com.kk2004.common.lock.DistributedLockFactory;
import com.kk2004.common.redis.RedisUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 尽力而为的文档计数器；MySQL 仍是真实来源。
 *
 * <p>Redis 数据访问全部经 kk-common {@link RedisUtil}：每页一个带
 * dbVersion 的整体 DTO（SDK 的 kkRedisTemplate JSON 序列化），锁内的
 * 读-改-写保证跨实例一致。锁统一来自 kk-common
 * {@link DistributedLockFactory}（名字遵循 kwiki:lock:&lt;purpose&gt;:&lt;resource&gt;），
 * 有界等待且只在确实持有时解锁；没有锁工厂时写路径失败关闭并排队修复，
 * 读路径直接回源数据库。Redis 故障同样不阻断已提交的互动。
 */
@Component
public class WikiStatisticsCache {
    private static final String KEY_PREFIX = "kwiki:wiki:stats:v2:";
    private static final String LOCK_PREFIX = "kwiki:lock:wiki-stats:";
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final long LOCK_WAIT_MILLIS = 5000;

    private final RedisUtil redis;
    private final JdbcOperations jdbc;
    private final DistributedLockFactory lockFactory;

    public WikiStatisticsCache(ObjectProvider<RedisUtil> redis,
                               ObjectProvider<JdbcOperations> jdbc,
                               ObjectProvider<DistributedLockFactory> lockFactory) {
        this.redis = redis == null ? null : redis.getIfAvailable();
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
        this.lockFactory = lockFactory == null ? null : lockFactory.getIfAvailable();
    }

    public Counters getOrLoad(long pageId, Supplier<Counters> loader) {
        if (redis == null) {
            return loader.get();
        }
        try {
            Counters cached = read(pageId);
            if (cached != null) {
                return cached;
            }
            Counters filled = withLock(pageId, () -> {
                Counters again = read(pageId);
                if (again != null) {
                    return again;
                }
                Counters loaded = loader.get();
                write(pageId, loaded);
                return loaded;
            });
            // 锁竞争/中断/无锁工厂时读路径可降级：直接回源，不填充缓存。
            return filled != null ? filled : loader.get();
        } catch (RuntimeException failure) {
            return loader.get();
        }
    }

    /** 仅在外层业务事务提交之后才应用增量。 */
    public void incrementAfterCommit(long pageId, long likes, long favorites, long comments) {
        incrementAfterCommit(pageId, likes, favorites, comments, -1L);
    }

    /** 感知版本的事务提交后增量。版本跳变或版本过期时从 MySQL 修复。 */
    public void incrementAfterCommit(long pageId, long likes, long favorites, long comments, long dbVersion) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            applyDeltas(pageId, likes, favorites, comments, dbVersion > 0 ? dbVersion : null);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                applyDeltas(pageId, likes, favorites, comments, dbVersion > 0 ? dbVersion : null);
            }
        });
    }

    public void invalidate(long pageId) {
        if (redis == null) return;
        try {
            if (!lockAndRun(pageId, () -> redis.del(KEY_PREFIX + pageId))) {
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
            if (!lockAndRun(pageId, () -> write(pageId, counters))) {
                requestRepair(pageId, "stats_lock_unavailable");
            }
        } catch (RuntimeException ignored) { }
    }

    /**
     * 锁内整体读-改-写。dbVersion 为 null 表示不感知版本（保持既有值）；
     * 否则仅当新版本恰好是缓存版本 + 1 时应用，跳变即作废缓存并排队修复。
     */
    private void applyDeltas(long pageId, long likes, long favorites, long comments, Long dbVersion) {
        if (redis == null) return;
        try {
            boolean applied = lockAndRun(pageId, () -> {
                Counters cached = read(pageId);
                if (cached == null) {
                    requestRepair(pageId, "cache_missing_before_delta");
                    return;
                }
                if (dbVersion != null && dbVersion != cached.dbVersion() + 1) {
                    requestRepair(pageId, "redis_version_gap");
                    redis.del(KEY_PREFIX + pageId);
                    return;
                }
                long nextLikes = cached.likes() + likes;
                long nextFavorites = cached.favorites() + favorites;
                long nextComments = cached.comments() + comments;
                if (nextLikes < 0 || nextFavorites < 0 || nextComments < 0) {
                    requestRepair(pageId, "stats_delta_negative");
                    redis.del(KEY_PREFIX + pageId);
                    return;
                }
                // 记录行存在时零增量也是一次有意为之的触达（续期 TTL）。
                write(pageId, new Counters(nextLikes, nextFavorites, nextComments,
                        dbVersion == null ? cached.dbVersion() : dbVersion));
            });
            if (!applied) requestRepair(pageId, "stats_lock_unavailable");
        } catch (RuntimeException failure) {
            requestRepair(pageId, "redis_update_failed");
        }
    }

    private void requestRepair(long pageId, String reason) {
        if (jdbc == null) return;
        try {
            jdbc.update("INSERT INTO stats_repair (page_id, reason) VALUES (?, ?)", pageId, reason);
        } catch (RuntimeException ignored) {
            // Redis 故障不得把一次已提交的互动变成失败的 API 调用。
        }
    }

    /** SDK JSON 序列化的整体 DTO；损坏的载荷按未命中处理。 */
    private Counters read(long pageId) {
        try {
            Object value = redis.get(KEY_PREFIX + pageId);
            return value instanceof Counters counters ? counters : null;
        } catch (RuntimeException corruptedOrUnavailable) {
            return null;
        }
    }

    private void write(long pageId, Counters value) {
        redis.set(KEY_PREFIX + pageId, value, TTL);
    }

    /** 获取每页细粒度锁并执行临界区；返回 false 表示未进入（超时/中断/无锁工厂）。 */
    private boolean lockAndRun(long pageId, Runnable action) {
        return withLock(pageId, () -> {
            action.run();
            return Boolean.TRUE;
        }) != null;
    }

    /**
     * 锁内执行并返回结果；未获取锁（超时、中断、无锁工厂）时返回 null。
     * 临界区自身不返回 null，因此 null 只表示"未进入"。
     */
    private <T> T withLock(long pageId, Supplier<T> section) {
        if (lockFactory == null) {
            // 互斥是正确性前提：没有锁工厂时写路径失败关闭，由调用方排队修复。
            return null;
        }
        DistributedLock lock = lockFactory.getDistributedLock(LOCK_PREFIX + pageId);
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!acquired) {
            return null;
        }
        try {
            return section.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public record Counters(long likes, long favorites, long comments, long dbVersion) {
        public Counters(long likes, long favorites, long comments) { this(likes, favorites, comments, 0L); }
    }
}
