package com.kwiki.wiki.access;

/**
 * 知识库成员关系角色。浏览者读取已发布内容，编辑者修改内容，
 * 所有者额外管理成员关系及知识库本身。
 */
public enum KnowledgeBaseRole {
    OWNER,
    ADMIN,
    EDITOR,
    VIEWER
}
