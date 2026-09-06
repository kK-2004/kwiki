package com.kwiki.wiki.access;

/**
 * Knowledge-base membership roles. Viewers read published content, editors change
 * content, owners additionally manage membership and the knowledge base itself.
 */
public enum KnowledgeBaseRole {
    OWNER,
    EDITOR,
    VIEWER
}
