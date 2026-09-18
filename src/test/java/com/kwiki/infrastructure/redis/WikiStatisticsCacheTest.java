package com.kwiki.infrastructure.redis;

import com.kk2004.common.lock.DistributedLock;
import com.kk2004.common.lock.DistributedLockFactory;
import com.kk2004.common.redis.RedisUtil;
import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 统计缓存经 SDK 接口的契约：DTO 读-改-写、版本跳变/缺失/负数时
 * 排队修复、锁竞争或 Redis 故障时降级数据库而不丢失已提交互动。
 * 全部经 mock RedisUtil/DistributedLockFactory 运行，无外部连接。
 */
class WikiStatisticsCacheTest {

    private static final String KEY = "kwiki:wiki:stats:v2:7";
    private static final String LOCK_NAME = "kwiki:lock:wiki-stats:7";

    private RedisUtil redis;
    private JdbcOperations jdbc;
    private DistributedLock lock;
    private WikiStatisticsCache cache;

    @BeforeEach
    void setUp() throws Exception {
        redis = mock(RedisUtil.class);
        jdbc = mock(JdbcOperations.class);
        lock = mock(DistributedLock.class);
        DistributedLockFactory lockFactory = mock(DistributedLockFactory.class);
        when(lockFactory.getDistributedLock(LOCK_NAME)).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        cache = new WikiStatisticsCache(StandardTestProperties.providerOf(redis),
                StandardTestProperties.providerOf(jdbc),
                StandardTestProperties.providerOf(lockFactory));
    }

    private static ObjectProvider<RedisUtil> noRedis() {
        return StandardTestProperties.nullProvider();
    }

    @Test
    void withoutRedisTheLoaderIsTheAnswer() {
        WikiStatisticsCache offline = new WikiStatisticsCache(noRedis(), null, null);
        var counters = new WikiStatisticsCache.Counters(3, 2, 1, 5);

        assertThat(offline.getOrLoad(7, () -> counters)).isSameAs(counters);
        offline.incrementAfterCommit(7, 1, 0, 0, 6);
        offline.invalidate(7);
        offline.replace(7, counters);
        verifyNoInteractions(jdbc);
    }

    @Test
    void cacheHitIsReturnedWithoutLocking() throws Exception {
        var cached = new WikiStatisticsCache.Counters(3, 2, 1, 5);
        when(redis.get(KEY)).thenReturn(cached);

        var loaded = new WikiStatisticsCache.Counters(99, 99, 99, 9);
        assertThat(cache.getOrLoad(7, () -> loaded)).isSameAs(cached);
        verify(lock, never()).tryLock(anyLong(), any(TimeUnit.class));
    }

    @Test
    void missUnderLockLoadsWritesAndReturnsTheLoadedValue() throws Exception {
        when(redis.get(KEY)).thenReturn(null, null);
        var loaded = new WikiStatisticsCache.Counters(3, 2, 1, 5);

        assertThat(cache.getOrLoad(7, () -> loaded)).isSameAs(loaded);
        verify(redis).set(KEY, loaded, Duration.ofMinutes(30));
        verify(lock).unlock();
    }

    @Test
    void lockContentionDegradesToTheLoaderWithoutCaching() throws Exception {
        when(redis.get(KEY)).thenReturn(null);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);
        var loaded = new WikiStatisticsCache.Counters(3, 2, 1, 5);

        assertThat(cache.getOrLoad(7, () -> loaded)).isSameAs(loaded);
        verify(redis, never()).set(anyString(), any(), any(Duration.class));
        verify(lock, never()).unlock();
    }

    @Test
    void redisFailureOnReadStillAnswersFromTheLoader() {
        when(redis.get(KEY)).thenThrow(new IllegalStateException("connection refused"));
        var loaded = new WikiStatisticsCache.Counters(3, 2, 1, 5);

        assertThat(cache.getOrLoad(7, () -> loaded)).isSameAs(loaded);
    }

    @Test
    void versionedIncrementAppliesTheExactNextVersion() {
        when(redis.get(KEY)).thenReturn(new WikiStatisticsCache.Counters(3, 2, 1, 5));

        cache.incrementAfterCommit(7, 1, 0, 2, 6);

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(redis).set(eq(KEY), value.capture(), eq(Duration.ofMinutes(30)));
        assertThat(value.getValue()).isEqualTo(new WikiStatisticsCache.Counters(4, 2, 3, 6));
        verifyNoInteractions(jdbc);
    }

    @Test
    void unversionedIncrementKeepsTheCachedVersionAndRefreshesTtl() {
        when(redis.get(KEY)).thenReturn(new WikiStatisticsCache.Counters(3, 2, 1, 5));

        cache.incrementAfterCommit(7, 1, 1, 1);

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(redis).set(eq(KEY), value.capture(), eq(Duration.ofMinutes(30)));
        assertThat(value.getValue()).isEqualTo(new WikiStatisticsCache.Counters(4, 3, 2, 5));
        verifyNoInteractions(jdbc);
    }

    @Test
    void versionGapInvalidatesTheEntryAndQueuesRepair() {
        when(redis.get(KEY)).thenReturn(new WikiStatisticsCache.Counters(3, 2, 1, 5));

        cache.incrementAfterCommit(7, 1, 0, 0, 7); // 期待 6，跳到 7

        verify(jdbc).update("INSERT INTO stats_repair (page_id, reason) VALUES (?, ?)", 7L,
                "redis_version_gap");
        verify(redis).del(KEY);
        verify(redis, never()).set(anyString(), any(), any(Duration.class));
    }

    @Test
    void missingEntryQueuesRepairInsteadOfCreatingFromTheDelta() {
        when(redis.get(KEY)).thenReturn(null);

        cache.incrementAfterCommit(7, 1, 0, 0, 6);

        verify(jdbc).update("INSERT INTO stats_repair (page_id, reason) VALUES (?, ?)", 7L,
                "cache_missing_before_delta");
        verify(redis, never()).set(anyString(), any(), any(Duration.class));
    }

    @Test
    void negativeResultInvalidatesTheEntryAndQueuesRepair() {
        when(redis.get(KEY)).thenReturn(new WikiStatisticsCache.Counters(0, 2, 1, 5));

        cache.incrementAfterCommit(7, -1, 0, 0, 6);

        verify(jdbc).update("INSERT INTO stats_repair (page_id, reason) VALUES (?, ?)", 7L,
                "stats_delta_negative");
        verify(redis).del(KEY);
    }

    @Test
    void unavailableLockQueuesRepairInsteadOfApplyingTheDelta() throws Exception {
        when(redis.get(KEY)).thenReturn(new WikiStatisticsCache.Counters(3, 2, 1, 5));
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        cache.incrementAfterCommit(7, 1, 0, 0, 6);

        verify(jdbc).update("INSERT INTO stats_repair (page_id, reason) VALUES (?, ?)", 7L,
                "stats_lock_unavailable");
        verify(redis, never()).set(anyString(), any(), any(Duration.class));
        verify(lock, never()).unlock();
    }

    @Test
    void interruptedLockAcquisitionRestoresTheFlagAndQueuesRepair() throws Exception {
        when(redis.get(KEY)).thenReturn(new WikiStatisticsCache.Counters(3, 2, 1, 5));
        when(lock.tryLock(anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("cancelled"));
        java.util.concurrent.atomic.AtomicBoolean interruptRestored =
                new java.util.concurrent.atomic.AtomicBoolean();

        Thread runner = new Thread(() -> {
            cache.incrementAfterCommit(7, 1, 0, 0, 6);
            interruptRestored.set(Thread.currentThread().isInterrupted());
        });
        runner.start();
        runner.join(5_000);

        assertThat(interruptRestored).as("interrupt flag restored in the waiting thread").isTrue();
        verify(jdbc).update("INSERT INTO stats_repair (page_id, reason) VALUES (?, ?)", 7L,
                "stats_lock_unavailable");
        verify(redis, never()).set(anyString(), any(), any(Duration.class));
        verify(lock, never()).unlock();
    }

    @Test
    void redisWriteFailureQueuesRepairAndNeverThrows() {
        when(redis.get(KEY)).thenReturn(new WikiStatisticsCache.Counters(3, 2, 1, 5));
        when(redis.set(anyString(), any(), any(Duration.class)))
                .thenThrow(new IllegalStateException("connection refused"));

        cache.incrementAfterCommit(7, 1, 0, 0, 6);

        verify(jdbc).update("INSERT INTO stats_repair (page_id, reason) VALUES (?, ?)", 7L,
                "redis_update_failed");
        verify(lock).unlock();
    }

    @Test
    void corruptedPayloadIsTreatedAsAMiss() {
        when(redis.get(KEY)).thenReturn("not-a-counters-payload");
        var loaded = new WikiStatisticsCache.Counters(3, 2, 1, 5);

        assertThat(cache.getOrLoad(7, () -> loaded)).isSameAs(loaded);
        verify(redis).set(KEY, loaded, Duration.ofMinutes(30));
    }

    @Test
    void replaceWritesUnderTheLock() {
        var counters = new WikiStatisticsCache.Counters(9, 8, 7, 4);

        cache.replace(7, counters);

        verify(redis).set(KEY, counters, Duration.ofMinutes(30));
        verify(lock).unlock();
    }

    @Test
    void invalidateDeletesUnderTheLock() {
        cache.invalidate(7);
        verify(redis).del(KEY);
        verify(lock).unlock();
    }
}
