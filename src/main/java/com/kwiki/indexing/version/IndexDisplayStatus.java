package com.kwiki.indexing.version;

/**
 * 管理端主展示状态（后端派生，优先级从高到低）：
 * NEEDS_ATTENTION（元数据/别名事实不一致，置顶且禁用危险操作）→
 * CATCHING_UP（切换准备中）→ REBUILDING（基线重建运行中）→
 * PUBLISHED（当前唯一别名目标）→ PENDING_REBUILD（待重建）→
 * REBUILT（已重建）。中文语义见管理端：待重建/重建中/已重建/补齐中/已发布。
 */
public enum IndexDisplayStatus {
    NEEDS_ATTENTION,
    CATCHING_UP,
    REBUILDING,
    PUBLISHED,
    PENDING_REBUILD,
    REBUILT
}
