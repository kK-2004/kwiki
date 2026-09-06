package com.kwiki.infrastructure.redis;

import com.kk2004.common.redis.RedisUtil;
import com.kwiki.wiki.access.AuthorizationScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The kk-common RedisUtil owns the Redis mechanics; this adapter only owns the
 * authorization contract: a cached scope round-trips without widening, and every
 * failure (feature off, Redis down, corrupted payload) degrades to the database
 * loader instead of widening or breaking the request.
 */
@ExtendWith(MockitoExtension.class)
class ScopeCacheTest {

    private static final String KEY = "kwiki:scope:v1:user:7";
    private static final long TTL_SECONDS = 60L;

    @Mock
    RedisUtil redisUtil;

    private ScopeCache cache;

    private final AuthorizationScope scope =
            new AuthorizationScope(7L, false, Set.of(1L, 3L), Map.of(1L, 4L, 3L, 1L));

    private final Function<Long, AuthorizationScope> loader = id -> scope;

    @BeforeEach
    void setUp() {
        lenient().when(redisUtil.getKey("kwiki:scope:v1:user:", "7")).thenReturn(KEY);
        cache = new ScopeCache(providerReturning(redisUtil), Duration.ofSeconds(TTL_SECONDS));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RedisUtil> providerReturning(RedisUtil util) {
        return new ObjectProvider<>() {
            @Override
            public RedisUtil getIfAvailable() {
                return util;
            }
        };
    }

    @Test
    void cacheHitReturnsCachedScopeWithoutCallingTheLoader() {
        when(redisUtil.queryWithPassThrough(eq("kwiki:scope:v1:user:"), eq(7L),
                eq(ScopeCache.CachedScope.class), any(), eq(TTL_SECONDS)))
                .thenReturn(ScopeCache.CachedScope.of(scope));

        AuthorizationScope loaded = cache.getOrLoad(7L, other -> {
            throw new AssertionError("loader must not run on a cache hit");
        });

        assertThat(loaded).isEqualTo(scope);
        assertThat(loaded.includes(1L)).isTrue();
        assertThat(loaded.includes(2L)).isFalse();
    }

    @Test
    void cacheMissLoadsThroughQueryWithPassThroughWithExplicitTtl() {
        when(redisUtil.queryWithPassThrough(eq("kwiki:scope:v1:user:"), eq(7L),
                eq(ScopeCache.CachedScope.class), any(), eq(TTL_SECONDS)))
                .thenAnswer(invocation -> {
                    Function<Long, ScopeCache.CachedScope> cacheLoader = invocation.getArgument(3);
                    return cacheLoader.apply(7L);
                });

        assertThat(cache.getOrLoad(7L, loader)).isEqualTo(scope);

        verify(redisUtil).queryWithPassThrough(eq("kwiki:scope:v1:user:"), eq(7L),
                eq(ScopeCache.CachedScope.class), any(), eq(TTL_SECONDS));
    }

    @Test
    void redisConnectionFailureDegradesToDatabaseLoad() {
        when(redisUtil.queryWithPassThrough(anyString(), anyLong(), any(), any(), anyLong()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(cache.getOrLoad(7L, loader)).isEqualTo(scope);
    }

    @Test
    void corruptedPayloadDegradesToDatabaseLoad() {
        when(redisUtil.queryWithPassThrough(anyString(), anyLong(), any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("corrupted payload"));

        assertThat(cache.getOrLoad(7L, loader)).isEqualTo(scope);
    }

    @Test
    void invalidateDeletesExactlyTheUserKey() {
        cache.invalidate(7L);
        verify(redisUtil).del(KEY);
    }

    @Test
    void invalidateFailureIsSwallowed() {
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(redisUtil).del(anyString());
        assertThatCode(() -> cache.invalidate(7L)).doesNotThrowAnyException();
    }

    @Test
    void missingRedisUtilBehavesAsPureDatabaseLoad() {
        ScopeCache noRedis = new ScopeCache(providerReturning(null), Duration.ofSeconds(TTL_SECONDS));
        assertThat(noRedis.getOrLoad(7L, loader)).isEqualTo(scope);
        assertThatCode(() -> noRedis.invalidate(7L)).doesNotThrowAnyException();
        verify(redisUtil, never()).del(anyString());
        verify(redisUtil, never()).queryWithPassThrough(anyString(), anyLong(), any(), any(), anyLong());
    }

    /**
     * The shared serializer must round-trip the cached scope shape without a second
     * JSON strategy: this exercises the exact GenericJackson2JsonRedisSerializer the
     * SDK's kkRedisTemplate wires, guarding against accidental default-typing drift.
     */
    @Test
    void scopeRoundTripsThroughTheSharedSerializerWithoutWidening() {
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();
        ScopeCache.CachedScope cached = ScopeCache.CachedScope.of(scope);

        byte[] bytes = serializer.serialize(cached);
        AuthorizationScope restored = ((ScopeCache.CachedScope) serializer.deserialize(bytes)).toScope();

        assertThat(restored).isEqualTo(scope);
        assertThat(restored.userId()).isEqualTo(7L);
        assertThat(restored.includes(3L)).isTrue();
        assertThat(restored.kbVersions()).isEqualTo(Map.of(1L, 4L, 3L, 1L));
    }
}
