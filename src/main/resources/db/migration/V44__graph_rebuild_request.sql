-- 内容/权限变化驱动的去抖自动重建请求：每库一行。
-- 标记：KnowledgeSourceChangePort 在推进 epoch 的同一事务内 upsert；
-- 调度：GraphRebuildScheduler 按 quiet-period/max-delay/hard_delete 提交单库构建。
CREATE TABLE graph_rebuild_request (
    kb_id                   BIGINT       NOT NULL,
    first_dirty_at          DATETIME(6)  NOT NULL,
    last_dirty_at           DATETIME(6)  NOT NULL,
    dirty_content_epoch     BIGINT       NOT NULL,
    dirty_security_epoch    BIGINT       NOT NULL,
    hard_delete             TINYINT(1)   NOT NULL DEFAULT 0,
    last_submitted_batch_id BIGINT       NULL,
    last_outcome            VARCHAR(30)  NULL,
    attempt_count           INT          NOT NULL DEFAULT 0,
    next_attempt_at         DATETIME(6)  NULL,
    updated_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (kb_id),
    CONSTRAINT ck_graph_rebuild_request_epochs
        CHECK (dirty_content_epoch >= 0 AND dirty_security_epoch >= 0),
    CONSTRAINT ck_graph_rebuild_request_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_graph_rebuild_request_outcome CHECK
        (last_outcome IS NULL OR last_outcome IN ('SUBMITTED', 'STALE', 'FAILED', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
