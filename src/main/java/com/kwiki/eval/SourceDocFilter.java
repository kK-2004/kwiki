package com.kwiki.eval;

import java.util.Map;
import java.util.Set;

/**
 * 按来源文档筛选参与关键词匹配的片段：只有 PAGE 资源且其页面映射到该题 sourceDocIds 的片段才算数。
 * 同领域文档按同一模板生成、关键词大量重复，不筛选时召回到别家文档的同类段落也会被算作命中。
 * sourceDocIds 为空（自建题）时不筛选。
 */
final class SourceDocFilter {

    static final String PAGE = "PAGE";

    private final Map<Long, Integer> pageDocIds;
    private final Set<Integer> sourceDocIds;

    SourceDocFilter(Map<Long, Integer> pageDocIds, EvalCase evalCase) {
        this.pageDocIds = pageDocIds == null ? Map.of() : pageDocIds;
        this.sourceDocIds = Set.copyOf(evalCase.sourceDocIds());
    }

    boolean admits(String resourceType, long resourceId) {
        if (sourceDocIds.isEmpty()) return true;
        if (!PAGE.equals(resourceType)) return false;
        Integer docId = pageDocIds.get(resourceId);
        return docId != null && sourceDocIds.contains(docId);
    }
}
