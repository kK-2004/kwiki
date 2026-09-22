package com.kwiki.graph;

import java.util.List;

/** 有来源约束的社区摘要。 */
public record CommunitySummary(
        String title,
        String summary,
        List<String> keywords,
        List<String> coreConcepts,
        List<String> importantRelations,
        List<String> questionTypes,
        List<GraphSourceRef> sourceRefs) {

    public CommunitySummary {
        if (title == null || title.isBlank() || summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("社区摘要标题和正文不能为空");
        }
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        coreConcepts = coreConcepts == null ? List.of() : List.copyOf(coreConcepts);
        importantRelations = importantRelations == null ? List.of() : List.copyOf(importantRelations);
        questionTypes = questionTypes == null ? List.of() : List.copyOf(questionTypes);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
