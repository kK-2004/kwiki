package com.kwiki.infrastructure.redis;

import com.kk2004.common.redis.RedisUtil;
import com.kwiki.wiki.access.AuthorizationScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Thin adapter over the shared kk-common {@link RedisUtil} for resolved authorization
 * scopes. The SDK owns every Redis concern (serialization via its kkRedisTemplate,
 * cache-penetration protection through queryWithPassThrough, explicit TTL); the only
 * app-specific behavior kept here is the authorization contract: Redis is an
 * optimization only, so every failure path — feature disabled, connection refused,
 * corrupted payload, failed cache write — degrades to a database load. Failures never
 * widen a scope and never break request handling.
 */
@Component
public class ScopeCache {

    private static final String KEY_PREFIX = "kwiki:scope:v1:user:";

    private final ObjectProvider<RedisUtil> redis;
    private final Duration ttl;

    public ScopeCache(ObjectProvider<RedisUtil> redis,
                      @org.springframework.beans.factory.annotation.Value(
                              "${kwiki.security.scope-cache-ttl:60s}") Duration ttl) {
        this.redis = redis.getIfAvailable() == null ? null : redis;
        this.ttl = ttl;
    }

    /**
     * Returns the cached scope, resolving through {@code loader} (and caching the
     * result) on a miss. Correctness always comes from the loader: with the Redis
     * feature disabled or on any Redis failure the loader result is returned directly.
     */
    public AuthorizationScope getOrLoad(long userId, Function<Long, AuthorizationScope> loader) {
        RedisUtil util = redis == null ? null : redis.getIfAvailable();
        if (util == null) {
            return loader.apply(userId);
        }
        try {
            CachedScope cached = util.queryWithPassThrough(KEY_PREFIX, userId, CachedScope.class,
                    id -> CachedScope.of(loader.apply(id)), ttl.toSeconds());
            return cached == null ? loader.apply(userId) : cached.toScope();
        } catch (RuntimeException e) {
            // fail-safe: the database, not the cache, is the source of truth
            return loader.apply(userId);
        }
    }

    /** Called on membership changes so stale scopes are never served. */
    public void invalidate(long userId) {
        RedisUtil util = redis == null ? null : redis.getIfAvailable();
        if (util == null) {
            return;
        }
        try {
            util.del(util.getKey(KEY_PREFIX, String.valueOf(userId)));
        } catch (RuntimeException e) {
            // best effort; scope versions still guard outbound traffic
        }
    }

    /**
     * Jackson-facing cached shape, serialized by the SDK's shared template. Uses
     * List/Map (not the domain record's JDK-immutable collections) so the shared
     * serializer round-trips it; no second ObjectMapper lives on the Redis path.
     */
    record CachedScope(long userId, boolean superuser,
                       List<Long> accessibleKbIds, Map<Long, Long> kbVersions) {

        static CachedScope of(AuthorizationScope scope) {
            // plain ArrayList/HashMap so the SDK's default-typing serializer stores and
            // restores concrete JDK types it can actually construct
            return new CachedScope(scope.userId(), scope.superuser(),
                    new ArrayList<>(scope.accessibleKbIds()), new HashMap<>(scope.kbVersions()));
        }

        AuthorizationScope toScope() {
            return new AuthorizationScope(userId, superuser,
                    Set.copyOf(accessibleKbIds), kbVersions);
        }
    }
}
