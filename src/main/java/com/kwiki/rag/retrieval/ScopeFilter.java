package com.kwiki.rag.retrieval;

import com.kwiki.wiki.access.AuthorizationScope;

import java.util.List;

/**
 * Middleware-free authorization filter shared by every recall branch. Infra
 * adapters translate it into their native query filter; the domain never sees
 * an Elasticsearch DSL type.
 */
public record ScopeFilter(boolean superuser, List<Long> kbIds) {

    public static ScopeFilter from(AuthorizationScope scope) {
        if (scope.superuser()) {
            return new ScopeFilter(true, List.of());
        }
        return new ScopeFilter(false, List.copyOf(scope.accessibleKbIds()));
    }
}
