package com.kwiki.indexing.version;

/**
 * 管理端主展示状态（后端派生，优先级从高到低）：
 * NEEDS_ATTENTION（元数据/别名事实不一致，置顶且禁用危险操作）→
 * MIGRATING（存量迁移运行中）→ PUBLISHED（当前唯一别名目标）→
 * PENDING_MIGRATION（待迁移：未构建、配置已改或未追平）→ MIGRATED（已迁移）。
 */
public enum IndexDisplayStatus {
    NEEDS_ATTENTION,
    MIGRATING,
    PUBLISHED,
    PENDING_MIGRATION,
    MIGRATED
}
