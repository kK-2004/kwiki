-- 图构建批次删除：批次与子任务行保留为墓碑（graphVersion / COMMUNITY 版本按 MAX+1 分配，
-- 物理删除行会让后续构建复用旧版本号并写进残留数据），管理端列表隐藏已删除批次；
-- 外部数据（ArcadeDB 该版本图数据、社区 ES 物理索引）在元数据提交后清理，结果记在批次上，可重试。
ALTER TABLE graph_build_batch
    ADD COLUMN deleted_at      DATETIME(6)   NULL,
    ADD COLUMN deleted_by      VARCHAR(100)  NULL,
    ADD COLUMN cleanup_state   VARCHAR(20)   NULL,
    ADD COLUMN cleanup_summary VARCHAR(1000) NULL,
    ADD CONSTRAINT ck_graph_build_batch_cleanup_state CHECK
        (cleanup_state IS NULL OR cleanup_state IN ('PENDING', 'DONE', 'FAILED'));
