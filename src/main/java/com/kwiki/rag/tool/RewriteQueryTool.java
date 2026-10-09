package com.kwiki.rag.tool;

import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.retrieval.QaRetrievalBudgets;
import com.kwiki.rag.rewrite.ConversationalRewriter;
import com.kwiki.rag.rewrite.DecompositionRewriter;
import com.kwiki.rag.rewrite.ExpansionRewriter;
import com.kwiki.rag.rewrite.FeedbackRewriteInput;
import com.kwiki.rag.rewrite.FeedbackRewritePort;
import com.kwiki.rag.rewrite.RewriteResult;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * rewrite_query：用现有的四类改写器改写当前检索 query。
 * 分解只产生候选子查询（供 planner 下一轮显式用于检索），其余模式直接更新当前 query。
 * 改写结果通过证据池状态而不是工具结果返回——工具结果只携带整数统计。
 */
@Component
public class RewriteQueryTool implements AgentToolExecutor {
    private final ConversationalRewriter conversational;
    private final ExpansionRewriter expansion;
    private final DecompositionRewriter decomposition;
    private final FeedbackRewritePort feedback;
    private final QaRetrievalBudgets budgets;
    private final AgenticLimits limits;

    public RewriteQueryTool(ConversationalRewriter conversational, ExpansionRewriter expansion,
                            DecompositionRewriter decomposition, FeedbackRewritePort feedback,
                            QaRetrievalBudgets budgets, AgenticLimits limits) {
        this.conversational = conversational;
        this.expansion = expansion;
        this.decomposition = decomposition;
        this.feedback = feedback;
        this.budgets = budgets;
        this.limits = limits;
    }

    @Override
    public String name() {
        return AgentTools.REWRITE_QUERY;
    }

    @Override
    public Outcome execute(AgentToolArguments arguments, ToolExecutionContext context) {
        if (!(arguments instanceof AgentToolArguments.RewriteQuery args)) {
            throw new ToolFailure("invalid-arguments");
        }
        EvidencePool pool = context.pool();
        if (pool.rewritesUsed() >= limits.rewrites()) {
            throw new ToolFailure("rewrite-budget-exhausted");
        }
        if (!context.run().canModel()) {
            throw new ToolFailure("model-budget-exhausted");
        }
        pool.useRewrite();
        String base = pool.currentQuery();
        List<String> candidates;
        String fallbackReason = null;
        switch (args.mode()) {
            case CONVERSATIONAL -> {
                RewriteResult result = conversational.rewrite(base, context.conversationHistory());
                candidates = result.effectiveQueries();
                fallbackReason = result.fallbackReason();
            }
            case EXPANSION -> {
                RewriteResult result = expansion.rewrite(base);
                candidates = result.effectiveQueries();
                fallbackReason = result.fallbackReason();
            }
            case DECOMPOSITION -> {
                RewriteResult result = decomposition.rewrite(base);
                candidates = result.effectiveQueries();
                fallbackReason = result.fallbackReason();
            }
            case FEEDBACK -> {
                Optional<String> next = feedback.rewrite(new FeedbackRewriteInput(
                        context.originalQuery(), pool.rewriteHistory(), base, pool.stageFailures(),
                        pool.latestReason(), budgets.base().finalTopK(), budgets.expanded().finalTopK(),
                        true, context.conversationHistory()));
                candidates = next.map(List::of).orElse(List.of());
            }
            default -> throw new ToolFailure("invalid-arguments");
        }
        List<String> changed = candidates.stream()
                .filter(query -> query != null && !query.isBlank() && !query.equals(base))
                .distinct()
                .toList();
        if (changed.isEmpty()) {
            throw new ToolFailure("rewrite-no-change");
        }
        pool.setRewriteSuggestions(changed);
        if (args.mode() != AgentToolArguments.RewriteKind.DECOMPOSITION) {
            pool.applyRewrite(changed.get(0));
        }
        return new Outcome(
                List.of(),
                fallbackReason == null ? List.of() : List.of(fallbackReason),
                Map.of("suggestionCount", changed.size()));
    }
}
