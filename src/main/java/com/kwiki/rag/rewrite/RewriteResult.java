package com.kwiki.rag.rewrite;

import com.kwiki.rag.routing.RewriteMode;

import java.util.List;

/** 改写结果：原文始终保留；生效查询是检索的输入。 */
public record RewriteResult(
        String original,
        RewriteMode mode,
        List<String> effectiveQueries,
        String fallbackReason) {

    public RewriteResult {
        effectiveQueries = effectiveQueries == null ? List.of() : List.copyOf(effectiveQueries);
    }

    public static RewriteResult none(String original) {
        return new RewriteResult(original, RewriteMode.NONE, List.of(original), null);
    }

    public static RewriteResult fallback(String original, String reason) {
        return new RewriteResult(original, RewriteMode.NONE, List.of(original), reason);
    }
}
