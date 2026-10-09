package com.kwiki.rag.tool;

/**
 * 当前轮次可向 planner 暴露哪些工具：expand_parents / graph_expand 需要证据池非空，
 * graph_expand 还需要图功能启用，rewrite_query 需要仍有改写与模型预算。
 */
public record ToolAvailability(boolean graphEnabled, boolean hasChildren, boolean canRewrite) {
}
