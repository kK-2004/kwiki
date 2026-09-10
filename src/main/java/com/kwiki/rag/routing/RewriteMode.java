package com.kwiki.rag.routing;

/** 查询改写策略；原始查询始终被保留以供审计。 */
public enum RewriteMode {
    NONE,
    CONVERSATIONAL,
    EXPANSION,
    DECOMPOSITION
}
