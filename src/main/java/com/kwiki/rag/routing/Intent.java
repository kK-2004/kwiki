package com.kwiki.rag.routing;

/**
 * 终止性路由意图。来自 k-Rag 的图相关意图与工具被
 * 有意排除：kwiki 不具备 Neo4j 或 Cypher 能力。
 */
public enum Intent {
    DIRECT_ANSWER,
    KNOWLEDGE_QA,
    PROCEDURAL,
    ANALYTICAL
}
