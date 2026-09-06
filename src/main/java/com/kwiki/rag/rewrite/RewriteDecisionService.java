package com.kwiki.rag.rewrite;

import com.kwiki.rag.routing.RetrievalPlan;
import com.kwiki.rag.routing.RewriteMode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Table-driven rewrite strategy selection from the route plan, query shape, and
 * bounded chat history. Explicit router decisions win; otherwise conversational
 * references, short/low-information queries, and compound shapes select their
 * strategies deterministically.
 */
@Component
public class RewriteDecisionService {

    private static final Pattern CONVERSATION_REFERENCE = Pattern.compile(
            "(它|他|她|这个|那个|上面|刚才|前面|它们|these|that|it|the above|just mentioned)");
    private static final int SHORT_QUERY_CHARS = 12;

    public RewriteMode decide(RetrievalPlan plan, String normalizedQuery,
                              List<ChatTurn> recentHistory) {
        if (plan.rewriteMode() == RewriteMode.DECOMPOSITION) {
            return RewriteMode.DECOMPOSITION;
        }
        if (hasConversationReference(normalizedQuery) && hasUserHistory(recentHistory)) {
            return RewriteMode.CONVERSATIONAL;
        }
        if (plan.rewriteMode() == RewriteMode.CONVERSATIONAL
                && hasUserHistory(recentHistory)) {
            return RewriteMode.CONVERSATIONAL;
        }
        if (isShortLowInformation(normalizedQuery)) {
            return RewriteMode.EXPANSION;
        }
        if (plan.rewriteMode() == RewriteMode.EXPANSION) {
            return RewriteMode.EXPANSION;
        }
        return RewriteMode.NONE;
    }

    private static boolean hasConversationReference(String normalizedQuery) {
        return CONVERSATION_REFERENCE.matcher(normalizedQuery).find();
    }

    private static boolean hasUserHistory(List<ChatTurn> history) {
        return history != null && history.stream().anyMatch(ChatTurn::isUser);
    }

    /**
     * Short AND low-information: bare noun-ish queries only. Question-shaped
     * queries (containing interrogatives) carry enough information as-is.
     */
    static boolean isShortLowInformation(String normalizedQuery) {
        if (normalizedQuery.length() >= SHORT_QUERY_CHARS) {
            return false;
        }
        if (INTERROGATIVE.matcher(normalizedQuery).find()) {
            return false;
        }
        if (normalizedQuery.matches(".*\\d.*")) {
            return false;
        }
        if (normalizedQuery.contains("\"") || normalizedQuery.contains("“")) {
            return false;
        }
        return normalizedQuery.trim().split("\\s+").length <= 3;
    }

    private static final Pattern INTERROGATIVE = Pattern.compile(
            "(什么|怎么|如何|为什么|哪些|哪个|多少|谁|what|how|why|which|who|\\?)");
}
