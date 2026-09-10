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
 * 基于共享 kk-common {@link RedisUtil} 的轻量适配器（adapter），用于已解析的授权
 * 范围。SDK 负责所有 Redis 相关事务（通过其 kkRedisTemplate 进行序列化（serialization）、
 * 借助 queryWithPassThrough 的缓存穿透保护、显式 TTL）；此处保留的唯一
 * 应用特定行为是授权契约：Redis 仅作为优化，因此任何失败路径——
 * 功能禁用、连接被拒、负载损坏、缓存写入失败——都会退化为数据库加载。
 * 失败绝不会扩大范围，也绝不会中断请求处理。
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
     * 返回缓存的作用域；未命中时通过 {@code loader} 解析
     * （并缓存结果）。正确性始终来自加载器：在 Redis
     * 功能禁用或任何 Redis 故障时，直接返回加载器的结果。
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
            // 故障安全：真相来源是数据库而非缓存
            return loader.apply(userId);
        }
    }

    /** 在成员关系变化时调用，以确保绝不返回过期的作用域。 */
    public void invalidate(long userId) {
        RedisUtil util = redis == null ? null : redis.getIfAvailable();
        if (util == null) {
            return;
        }
        try {
            util.del(util.getKey(KEY_PREFIX, String.valueOf(userId)));
        } catch (RuntimeException e) {
            // 尽力而为；出站流量仍由作用域版本把关
        }
    }

    /**
     * 面向 Jackson 的缓存形态，由 SDK 的共享模板序列化。使用
     * List/Map（而非领域 record 的 JDK 不可变集合），以便共享
     * 序列化器能够往返；Redis 链路上不存在第二个 ObjectMapper。
     */
    record CachedScope(long userId, boolean superuser,
                       List<Long> accessibleKbIds, Map<Long, Long> kbVersions,
                       List<Long> accessiblePageIds) {

        CachedScope {
            accessibleKbIds = accessibleKbIds == null ? List.of() : accessibleKbIds;
            kbVersions = kbVersions == null ? Map.of() : kbVersions;
            accessiblePageIds = accessiblePageIds == null ? List.of() : accessiblePageIds;
        }

        static CachedScope of(AuthorizationScope scope) {
            // 使用普通 ArrayList/HashMap，以便 SDK 的默认类型序列化器能够存储并
            // 还原它确实能够构造的具体 JDK 类型
            return new CachedScope(scope.userId(), scope.superuser(),
                    new ArrayList<>(scope.accessibleKbIds()), new HashMap<>(scope.kbVersions()),
                    new ArrayList<>(scope.accessiblePageIds()));
        }

        AuthorizationScope toScope() {
            return new AuthorizationScope(userId, superuser,
                    Set.copyOf(accessibleKbIds), kbVersions, Set.copyOf(accessiblePageIds));
        }
    }
}
