package com.kwiki.rag.rewrite;

import com.kwiki.rag.routing.RetrievalPlan;
import com.kwiki.rag.routing.RewriteMode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 依据路由计划、查询形态与有界对话历史，
 * 以表格驱动的方式选择改写策略。路由器的显式决策优先；否则
 * 对话指代、短查询/低信息量查询以及复合形态会以确定性方式
 * 选择各自的策略。
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
     * 既短又信息量低：仅限裸名词式查询。问句形态的
     * 查询（含疑问词）本身已携带足够信息。
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
