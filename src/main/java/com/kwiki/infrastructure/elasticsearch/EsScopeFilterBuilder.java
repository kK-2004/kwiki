package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.kwiki.rag.retrieval.ScopeFilter;

import java.util.List;

/**
 * Builds the single authorization filter shared by every recall branch, applied
 * BEFORE TopK selection. Superusers get a permissive filter; empty scopes get
 * match-none so no candidate can ever occupy a rank.
 */
/** Translates the domain ScopeFilter into the shared Elasticsearch filter. */
public final class EsScopeFilterBuilder {

    private EsScopeFilterBuilder() {
    }

    public static Query build(ScopeFilter scope) {
        if (scope.superuser()) {
            return Query.of(query -> query.matchAll(matchAll -> matchAll));
        }
        if (scope.kbIds().isEmpty()) {
            return Query.of(query -> query.matchNone(matchNone -> matchNone));
        }
        List<FieldValue> kbIds = scope.kbIds().stream().sorted()
                .map(kbId -> new FieldValue.Builder().longValue(kbId).build())
                .toList();
        return Query.of(query -> query.terms(terms -> terms
                .field("kbId")
                .terms(values -> values.value(kbIds))));
    }
}
