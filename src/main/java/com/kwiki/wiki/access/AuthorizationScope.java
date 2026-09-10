package com.kwiki.wiki.access;

import java.util.Map;
import java.util.Set;
import java.util.function.LongUnaryOperator;

/**
 * 每次请求解析一次的不可变授权作用域。携带可访问的知识库以及解析时刻捕获的
 * 各知识库作用域版本；出站守卫在证据离开系统前会重新校验这些版本，因此
 * 长时间检索/生成期间发生的成员关系变更会使请求中止。
 */
public record AuthorizationScope(
        long userId,
        boolean superuser,
        Set<Long> accessibleKbIds,
        Map<Long, Long> kbVersions,
        Set<Long> accessiblePageIds) {

    public AuthorizationScope(long userId, boolean superuser, Set<Long> accessibleKbIds,
                              Map<Long, Long> kbVersions) {
        this(userId, superuser, accessibleKbIds, kbVersions, Set.of());
    }

    public AuthorizationScope {
        accessibleKbIds = Set.copyOf(accessibleKbIds);
        kbVersions = Map.copyOf(kbVersions);
        accessiblePageIds = Set.copyOf(accessiblePageIds == null ? Set.of() : accessiblePageIds);
    }

    /** 超级用户可见全部内容；其他用户仅可见其作为成员的知识库。 */
    public boolean includes(long kbId) {
        return superuser || accessibleKbIds.contains(kbId);
    }

    public boolean includesPage(long pageId) {
        return superuser || accessiblePageIds.contains(pageId);
    }

    /**
     * 当任一被追踪的知识库版本已超出本作用域捕获的值（即解析后成员关系发生变更）时为 true。
     */
    public boolean isStale(LongUnaryOperator currentVersion) {
        for (Map.Entry<Long, Long> entry : kbVersions.entrySet()) {
            if (currentVersion.applyAsLong(entry.getKey()) > entry.getValue()) {
                return true;
            }
        }
        return false;
    }
}
