package com.kwiki.rag.tool;

import com.kwiki.rag.answer.ParentEvidence;
import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.retrieval.ChildEvidence;
import com.kwiki.rag.retrieval.QaParentFetchService;
import com.kwiki.rag.retrieval.QaRetrievalBudgets;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * expand_parents：对证据池里（或 planner 指定的子集）的子块获取其父块完整正文，
 * 受阶段父块上限与固定父块字符预算约束，并重新校验授权与生命周期。
 */
@Component
public class ExpandParentsTool implements AgentToolExecutor {
    private final QaParentFetchService parentFetch;
    private final QaRetrievalBudgets budgets;
    private final AgenticLimits limits;

    public ExpandParentsTool(QaParentFetchService parentFetch, QaRetrievalBudgets budgets,
                             AgenticLimits limits) {
        this.parentFetch = parentFetch;
        this.budgets = budgets;
        this.limits = limits;
    }

    @Override
    public String name() {
        return AgentTools.EXPAND_PARENTS;
    }

    @Override
    public Outcome execute(AgentToolArguments arguments, ToolExecutionContext context) {
        if (!(arguments instanceof AgentToolArguments.ExpandParents args)) {
            throw new ToolFailure("invalid-arguments");
        }
        EvidencePool pool = context.pool();
        if (!pool.hasChildren()) {
            throw new ToolFailure("no-evidence");
        }
        List<ChildEvidence> selected;
        if (args.childChunkKeys().isEmpty()) {
            selected = pool.children();
        } else {
            selected = new ArrayList<>();
            for (String key : args.childChunkKeys()) {
                selected.add(pool.child(key).orElseThrow(() -> new ToolFailure("unknown-child-key")));
            }
        }
        if (pool.parentFetches() >= limits.parentFetches()) {
            throw new ToolFailure("parent-budget-exhausted");
        }
        pool.useParentFetch();
        var stage = args.range() == AgentToolArguments.Range.EXPANDED
                ? budgets.expanded() : budgets.base();
        var outcome = parentFetch.fetchParents(context.run().scope, context.run(), selected,
                stage.parentLimit(), budgets.parentCharBudget, pool.usedParentKeys());
        if (outcome.skipped()) {
            return new Outcome(List.of(), List.of("parent-fetch-skipped:" + outcome.reason()), Map.of(
                    "parentCount", pool.parents().size(),
                    "addedCount", 0,
                    "childCount", pool.children().size()));
        }
        List<String> keys = outcome.evidence().stream().map(ParentEvidence::parentChunkKey).toList();
        int added = pool.addParents(outcome.evidence());
        return new Outcome(keys, List.of(), Map.of(
                "parentCount", pool.parents().size(),
                "addedCount", added,
                "childCount", pool.children().size()));
    }
}
