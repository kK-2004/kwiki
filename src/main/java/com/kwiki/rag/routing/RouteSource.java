package com.kwiki.rag.routing;

/** 路由决策的来源，用于审计与回退指标。 */
public enum RouteSource {
    RULE,
    LLM,
    FALLBACK
}
