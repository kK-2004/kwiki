package com.kwiki.rag.tool;

import com.kwiki.graph.GraphSelectionScope;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.rag.rewrite.ChatTurn;

import java.util.List;

/** 执行器的运行上下文：可信的 run、证据池、原始问题、对话历史、图选择范围、当前查询轮次（仅用于日志）。 */
public record ToolExecutionContext(
        RunContext run,
        EvidencePool pool,
        String originalQuery,
        List<ChatTurn> conversationHistory,
        GraphSelectionScope graphSelection,
        int round) {

    public ToolExecutionContext {
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
        graphSelection = graphSelection == null ? GraphSelectionScope.all() : graphSelection;
    }
}
