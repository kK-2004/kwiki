package com.kwiki.rag.tool;

import java.util.List;

/** QA agent 可规划的工具名。 */
public final class AgentTools {
    public static final String SEARCH_CHUNKS = "search_chunks";
    public static final String EXPAND_PARENTS = "expand_parents";
    public static final String GRAPH_EXPAND = "graph_expand";
    public static final String REWRITE_QUERY = "rewrite_query";
    public static final String ASK_USER = "ask_user";

    public static final List<String> ALL =
            List.of(SEARCH_CHUNKS, EXPAND_PARENTS, GRAPH_EXPAND, REWRITE_QUERY, ASK_USER);

    private AgentTools() {
    }
}
