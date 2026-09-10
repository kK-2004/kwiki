package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.kwiki.rag.retrieval.ScopeFilter;

import java.util.List;

/**
 * Builds the single authorization + lifecycle filter shared by every recall
 * branch, applied BEFORE TopK selection. Superusers get a permissive filter
 * that still excludes archived resources; empty scopes get match-none so no
 * candidate can ever occupy a rank. Archived pages/knowledge bases are
 * excluded even for members whose membership rows still exist.
 */
public final class EsScopeFilterBuilder {

    private EsScopeFilterBuilder() {
    }

    public static Query build(ScopeFilter scope) {
        return build(scope, null);
    }

    /**
     * @param exclusions archived resource ids to exclude from every branch;
     *                   null means "no lifecycle information available" and
     *                   must only be used by callers that already enforce the
     *                   lifecycle through the authoritative database loader.
     */
    public static Query build(ScopeFilter scope,
                              com.kwiki.rag.retrieval.RetrievalLifecycleService.Exclusions exclusions) {
        Query base = baseScope(scope);
        if (exclusions == null || exclusions.isEmpty()) {
            return base;
        }
        List<FieldValue> archivedKbs = exclusions.archivedKbIds().stream().sorted()
                .map(kbId -> new FieldValue.Builder().longValue(kbId).build()).toList();
        List<FieldValue> archivedPages = exclusions.archivedPageIds().stream().sorted()
                .map(pageId -> new FieldValue.Builder().longValue(pageId).build()).toList();
        return Query.of(query -> query.bool(bool -> bool
                .filter(base)
                .mustNot(mustNot -> mustNot.bool(nested -> {
                    if (!archivedKbs.isEmpty()) {
                        nested.should(should -> should.terms(terms -> terms
                                .field("kbId")
                                .terms(values -> values.value(archivedKbs))));
                    }
                    if (!archivedPages.isEmpty()) {
                        nested.should(should -> should.bool(pageBool -> pageBool
                                .filter(filter -> filter.term(term -> term
                                        .field("resourceType").value("PAGE")))
                                .filter(filter -> filter.terms(terms -> terms
                                        .field("resourceId")
                                        .terms(values -> values.value(archivedPages))))));
                    }
                    return nested.minimumShouldMatch("1");
                }))));
    }

    private static Query baseScope(ScopeFilter scope) {
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
