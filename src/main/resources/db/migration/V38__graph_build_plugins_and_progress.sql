-- 图构建环节插件与进度：插件 id 记入批次、子任务、快照，便于追溯产出来源。
ALTER TABLE graph_build_batch ADD COLUMN extractor VARCHAR(64) NULL,
    ADD COLUMN community_detector VARCHAR(64) NULL, ADD COLUMN summarizer VARCHAR(64) NULL;
UPDATE graph_build_batch SET extractor = 'kwiki-extractor-1',
    community_detector = 'arcadedb-leiden-1', summarizer = 'kwiki-summarizer-1';
ALTER TABLE graph_build_batch MODIFY COLUMN extractor VARCHAR(64) NOT NULL,
    MODIFY COLUMN community_detector VARCHAR(64) NOT NULL, MODIFY COLUMN summarizer VARCHAR(64) NOT NULL;

ALTER TABLE graph_build_run ADD COLUMN extractor VARCHAR(64) NULL,
    ADD COLUMN community_detector VARCHAR(64) NULL, ADD COLUMN summarizer VARCHAR(64) NULL;
UPDATE graph_build_run SET extractor = 'kwiki-extractor-1',
    community_detector = 'arcadedb-leiden-1', summarizer = 'kwiki-summarizer-1';
ALTER TABLE graph_build_run MODIFY COLUMN extractor VARCHAR(64) NOT NULL,
    MODIFY COLUMN community_detector VARCHAR(64) NOT NULL, MODIFY COLUMN summarizer VARCHAR(64) NOT NULL;

ALTER TABLE graph_snapshot ADD COLUMN extractor VARCHAR(64) NULL,
    ADD COLUMN community_detector VARCHAR(64) NULL, ADD COLUMN summarizer VARCHAR(64) NULL;
UPDATE graph_snapshot SET extractor = 'kwiki-extractor-1',
    community_detector = 'arcadedb-leiden-1', summarizer = 'kwiki-summarizer-1';
ALTER TABLE graph_snapshot MODIFY COLUMN extractor VARCHAR(64) NOT NULL,
    MODIFY COLUMN community_detector VARCHAR(64) NOT NULL, MODIFY COLUMN summarizer VARCHAR(64) NOT NULL;

-- 子任务进度（执行器带隔离令牌更新）
ALTER TABLE graph_build_run
    ADD COLUMN sources_total BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN sources_extracted BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN extraction_cache_hits BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN communities_total BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN communities_summarized BIGINT NOT NULL DEFAULT 0;
