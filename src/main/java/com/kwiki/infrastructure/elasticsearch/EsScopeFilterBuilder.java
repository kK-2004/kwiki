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
        if (scope.kbIds().isEmpty() && scope.pageIds().isEmpty()) {
            return Query.of(query -> query.matchNone(matchNone -> matchNone));
        }
        List<FieldValue> kbIds = scope.kbIds().stream().sorted()
                .map(kbId -> new FieldValue.Builder().longValue(kbId).build()).toList();
        Query kbScope = scope.kbIds().isEmpty() ? Query.of(query -> query.matchNone(matchNone -> matchNone))
                : Query.of(query -> query.terms(terms -> terms.field("kbId").terms(values -> values.value(kbIds))));
        if (scope.pageIds().isEmpty()) return kbScope;
        List<FieldValue> pageIds = scope.pageIds().stream().sorted()
                .map(pageId -> new FieldValue.Builder().longValue(pageId).build()).toList();
        Query pageScope = Query.of(query -> query.bool(bool -> bool
                .filter(filter -> filter.term(term -> term.field("resourceType").value("PAGE")))
                .filter(filter -> filter.terms(terms -> terms.field("resourceId").terms(values -> values.value(pageIds))))));
        Query nonPageKbScope = Query.of(query -> query.bool(bool -> bool
                .filter(kbScope)
                .mustNot(mustNot -> mustNot.term(term -> term.field("resourceType").value("PAGE")))));
        return Query.of(query -> query.bool(bool -> bool.should(pageScope).should(nonPageKbScope).minimumShouldMatch("1")));
    }
}
