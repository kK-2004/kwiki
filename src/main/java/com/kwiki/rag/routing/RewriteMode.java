package com.kwiki.rag.routing;

/** Query rewrite strategies; the original query is always preserved for audit. */
public enum RewriteMode {
    NONE,
    CONVERSATIONAL,
    EXPANSION,
    DECOMPOSITION
}
