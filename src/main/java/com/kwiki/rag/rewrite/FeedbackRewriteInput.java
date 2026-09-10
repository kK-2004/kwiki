package com.kwiki.rag.rewrite;

import java.util.List;

/**
 * Query Rewrite Agent 的结构化反馈输入。原始查询
 * 不可变且始终存在；每次历史改写（按序）、实际
 * 已检索的查询、所有阶段拒绝原因，以及精确的 TopK
 * 扩展事实都随请求传递。字段长度限制作用于
 * 模型调用之前；被截断的字段会做标记，以免诊断将
 * 缩短的副本误认为真实模型输入。
 */
public record FeedbackRewriteInput(
        String originalQuery,
        List<String> previousRewrites,
        String lastQuery,
        List<String> stageFailures,
    String latestReason,
    int topKBefore,
    int topKAfter,
    boolean expansionAttempted,
    List<ChatTurn> conversationContext) {

    public FeedbackRewriteInput {
        previousRewrites = List.copyOf(previousRewrites == null ? List.of() : previousRewrites);
        stageFailures = List.copyOf(stageFailures == null ? List.of() : stageFailures);
        conversationContext =
                List.copyOf(conversationContext == null ? List.of() : conversationContext);
    }
}
