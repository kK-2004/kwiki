package com.kwiki.wiki.access;

/**
 * 可对知识库进行授权鉴定的服务端动作。导航、内容读取、搜索/检索以及历史记录
 * 均为显式动作，因此每个端点都能精确映射到其中之一。
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
