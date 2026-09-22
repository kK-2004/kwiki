package com.kwiki.graph;

import java.util.HashSet;
import java.util.Set;

/** 校验模型摘要只能引用被选入输入的真实来源。 */
public final class CommunitySummaryValidator {

    private CommunitySummaryValidator() {
    }

    public static void requireValid(CommunitySummaryInput input, CommunitySummary summary) {
        if (summary == null) {
            throw new IllegalArgumentException("社区摘要不能为空");
        }
        Set<String> allowed = new HashSet<>();
        for (GraphSourceChunk source : input.representativeSources()) {
            allowed.add(source.sourceChunkId());
        }
        for (GraphSourceRef ref : summary.sourceRefs()) {
            if (!allowed.contains(ref.sourceChunkId())) {
                throw new IllegalArgumentException("社区摘要引用了未提供的来源");
            }
        }
        if (!input.representativeRelations().isEmpty() && summary.sourceRefs().isEmpty()) {
            throw new IllegalArgumentException("含关系的社区摘要必须保留来源引用");
        }
    }
}
