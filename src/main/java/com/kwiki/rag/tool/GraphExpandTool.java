package com.kwiki.rag.tool;

import com.kwiki.rag.retrieval.GraphRetrievalEnhancer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * graph_expand：以证据池里的子块为种子做确定性图增强（社区召回 + 有界图扩展 + RRF 融合），
 * 与主链路共享 run 级图预算。图功能未启用（没有增强器 bean）时工具不可用；
 * 图分支失败只体现为降级项，证据池保持不变。
 */
@Component
public class GraphExpandTool implements AgentToolExecutor {
    private final ObjectProvider<GraphRetrievalEnhancer> enhancer;

    public GraphExpandTool(ObjectProvider<GraphRetrievalEnhancer> enhancer) {
        this.enhancer = enhancer;
    }

    @Override
    public String name() {
        return AgentTools.GRAPH_EXPAND;
    }

    @Override
    public Outcome execute(AgentToolArguments arguments, ToolExecutionContext context) {
        if (!(arguments instanceof AgentToolArguments.GraphExpand args)) {
            throw new ToolFailure("invalid-arguments");
        }
        GraphRetrievalEnhancer graph = enhancer.getIfAvailable();
        if (graph == null) {
            throw new ToolFailure("tool-unavailable");
        }
        EvidencePool pool = context.pool();
        if (!pool.hasChildren()) {
            throw new ToolFailure("no-evidence");
        }
        String query = args.query() == null || args.query().isBlank() ? pool.currentQuery() : args.query();
        Set<String> before = pool.childKeys();
        var result = graph.enhance(
                context.run().scope.userId(), context.run().scope.superuser(),
                pool.children(), query, context.graphSelection(),
                pool.embeddingCache(), pool.graphBudget(), false);
        if (result.enhanced()) {
            pool.replaceChildren(result.mergedChildren());
        }
        List<String> added = new ArrayList<>(pool.childKeys());
        added.removeAll(before);
        return new Outcome(added, result.degradations(), Map.of(
                "addedCount", added.size(),
                "childCount", pool.children().size()));
    }
}
