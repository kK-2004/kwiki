package com.kwiki.rag.rewrite;

import java.util.List;

/**
 * Structured feedback input of the Query Rewrite Agent. The original query is
 * immutable and always present; every historical rewrite (in order), the last
 * query actually searched, all stage rejection reasons, and the exact TopK
 * expansion facts travel with the request. Field length bounds apply before
 * the model call; truncated fields are marked so the diagnosis never mistakes
 * a shortened copy for the real model input.
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
