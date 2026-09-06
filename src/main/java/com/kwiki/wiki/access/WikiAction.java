package com.kwiki.wiki.access;

/**
 * Server-side actions that can be authorized against a knowledge base. Navigation,
 * content reads, search/retrieval, and history are all explicit actions so every
 * endpoint can map to exactly one of them.
 */
public enum WikiAction {
    READ_PAGE,
    VIEW_REVISION_HISTORY,
    SEARCH_AND_RETRIEVE,
    CREATE_PAGE,
    EDIT_PAGE,
    ARCHIVE_PAGE,
    RESTORE_REVISION,
    UPLOAD_ATTACHMENT,
    MANAGE_MEMBERS,
    UPDATE_KNOWLEDGE_BASE,
    ARCHIVE_KNOWLEDGE_BASE
}
