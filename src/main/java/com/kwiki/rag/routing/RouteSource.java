package com.kwiki.rag.routing;

/** Where a routing decision came from, for audit and fallback metrics. */
public enum RouteSource {
    RULE,
    LLM,
    FALLBACK
}
