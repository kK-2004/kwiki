package com.kwiki.rag.retrieval;

import com.kwiki.wiki.access.AuthorizationScope;

import java.util.List;

/**
 * 无中间件、被所有召回分支共享的授权过滤器。底层
 * 适配器将其翻译为各自的原生查询过滤器；领域层
 * 从不直接接触 Elasticsearch DSL 类型。
 */
public record ScopeFilter(boolean superuser, List<Long> kbIds, List<Long> pageIds) {

    public ScopeFilter(boolean superuser, List<Long> kbIds) {
        this(superuser, kbIds, List.of());
    }

    public static ScopeFilter from(AuthorizationScope scope) {
        if (scope.superuser()) {
            return new ScopeFilter(true, List.of(), List.of());
        }
        return new ScopeFilter(false, List.copyOf(scope.accessibleKbIds()), List.copyOf(scope.accessiblePageIds()));
    }
}
