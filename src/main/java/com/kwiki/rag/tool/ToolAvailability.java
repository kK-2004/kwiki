package com.kwiki.rag.tool;

/**
 * 当前轮次可向 planner 暴露哪些工具：expand_parents / graph_expand / ask_user 需要证据池非空，
 * graph_expand 还需要图功能启用，rewrite_query 需要仍有改写与模型预算，
 * ask_user 还需要允许反问（非反问续跑、本次 run 尚未反问）。
 */
public record ToolAvailability(boolean graphEnabled, boolean hasChildren, boolean canRewrite, boolean canAsk) {

    /** 不允许反问的 3 参构造，保持 P2 的调用方不变。 */
    public ToolAvailability(boolean graphEnabled, boolean hasChildren, boolean canRewrite) {
        this(graphEnabled, hasChildren, canRewrite, false);
    }
}
