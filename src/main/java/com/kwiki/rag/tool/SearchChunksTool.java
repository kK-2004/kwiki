package com.kwiki.rag.tool;

import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.rag.retrieval.QaChildRetrievalService;
import com.kwiki.rag.retrieval.QaRetrievalBudgets;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * search_chunks：对每个查询做一次独立的子块召回（与主链路相同的 ACL、生命周期过滤与标准 RRF），
 * 把命中合并进证据池。BASE / EXPANDED 映射 QaRetrievalBudgets 的两档深度。
 */
@Component
public class SearchChunksTool implements AgentToolExecutor {
    private final QaChildRetrievalService retrieval;
    private final QaRetrievalBudgets budgets;
    private final AgenticLimits limits;

    public SearchChunksTool(QaChildRetrievalService retrieval, QaRetrievalBudgets budgets,
                            AgenticLimits limits) {
        this.retrieval = retrieval;
        this.budgets = budgets;
        this.limits = limits;
    }

    @Override
    public String name() {
        return AgentTools.SEARCH_CHUNKS;
    }

    @Override
    public Outcome execute(AgentToolArguments arguments, ToolExecutionContext context) {
        if (!(arguments instanceof AgentToolArguments.SearchChunks args)) {
            throw new ToolFailure("invalid-arguments");
        }
        EvidencePool pool = context.pool();
        RunContext run = context.run();
        if (pool.hybridRetrievals() + args.queries().size() > limits.hybridRetrievals()) {
            throw new ToolFailure("retrieval-budget-exhausted");
        }
        var stage = args.range() == AgentToolArguments.Range.EXPANDED
                ? budgets.expanded() : budgets.base();
        Set<String> before = pool.childKeys();
        List<String> degradations = new ArrayList<>();
        for (String query : args.queries()) {
            pool.useHybridRetrieval();
            var outcome = retrieval.retrieveChildren(
                    run.scope, run, query, stage, pool.embeddingCache(), args.strategy());
            degradations.addAll(outcome.degradations());
            pool.mergeChildren(outcome.children(), stage.childCharBudget());
        }
        List<String> added = new ArrayList<>(pool.childKeys());
        added.removeAll(before);
        return new Outcome(added, degradations, Map.of(
                "queryCount", args.queries().size(),
                "addedCount", added.size(),
                "childCount", pool.children().size()));
    }
}
