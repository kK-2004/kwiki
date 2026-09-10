package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.kwiki.rag.retrieval.ScopeFilter;

import java.util.List;

/**
 * 构建被每个召回（recall）分支共享的单一授权（authorization）+ 生命周期
 * 过滤器，在 TopK 选择之前应用。超级用户获得宽松的过滤器，
 * 但仍排除已归档资源；空范围获得 match-none，因此任何候选
 * 都不会占据排序位。已归档的页面/知识库即使对成员关系记录
 * 仍然存在的成员也会被排除。
 */
public final class EsScopeFilterBuilder {

    private EsScopeFilterBuilder() {
    }

    public static Query build(ScopeFilter scope) {
        return build(scope, null);
    }

    /**
     * @param exclusions 要从每个分支中排除的已归档资源 id；
     * null 表示「没有可用的生命周期信息」，只能由
     * 已通过权威数据库加载器强制校验生命周期的
     * 调用方使用。
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
