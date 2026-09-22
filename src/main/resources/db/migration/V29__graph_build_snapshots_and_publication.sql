-- 图构建批次、来源清单、不可变快照、社区索引版本和发布配对元数据。
-- Chunk 版本、COMMUNITY 版本、graph 版本和 mapping 版本分别保存。
CREATE TABLE community_index_version (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    version_number        BIGINT       NOT NULL,
    batch_id              BIGINT       NULL,
    physical_name_pattern VARCHAR(160) NOT NULL,
    mapping_schema_version INT          NOT NULL,
    config_revision       BIGINT       NOT NULL,
    state                 VARCHAR(20)  NOT NULL DEFAULT 'ALLOCATED',
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_community_index_version_number UNIQUE (version_number),
    CONSTRAINT ck_community_index_version_positive CHECK (version_number >= 1),
    CONSTRAINT ck_community_index_version_state CHECK
        (state IN ('ALLOCATED', 'BUILDING', 'BUILT', 'RETIRED', 'DELETING'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE graph_build_batch (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    idempotency_key       VARCHAR(240) NOT NULL,
    scope_kind            VARCHAR(30)  NOT NULL,
    requested_kb_ids_json JSON         NOT NULL,
    chunk_index_version   INT          NOT NULL,
    chunk_physical_index  VARCHAR(100) NOT NULL,
    community_index_version BIGINT     NOT NULL,
    state                 VARCHAR(30)  NOT NULL DEFAULT 'QUEUED',
    auto_publish          TINYINT(1)   NOT NULL DEFAULT 0,
    requested_by          VARCHAR(100) NOT NULL,
    schedule_date         DATE         NULL,
    failure_summary       VARCHAR(1000) NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version          BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_graph_build_batch_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_graph_build_batch_schedule UNIQUE (schedule_date),
    CONSTRAINT fk_graph_build_batch_community_version FOREIGN KEY (community_index_version)
        REFERENCES community_index_version (version_number),
    CONSTRAINT ck_graph_build_batch_scope CHECK (scope_kind IN ('ALL', 'KNOWLEDGE_BASE')),
    CONSTRAINT ck_graph_build_batch_state CHECK
        (state IN ('QUEUED', 'RUNNING', 'READY', 'PARTIAL_FAILED', 'FAILED', 'CANCELLED', 'STALE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE community_index_version
    ADD CONSTRAINT fk_community_index_version_batch FOREIGN KEY (batch_id)
        REFERENCES graph_build_batch (id);

CREATE TABLE graph_build_run (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id              BIGINT       NOT NULL,
    kb_id                 BIGINT       NOT NULL,
    chunk_index_version   INT          NOT NULL,
    chunk_physical_index  VARCHAR(100) NOT NULL,
    community_index_version BIGINT     NOT NULL,
    community_physical_index VARCHAR(160) NOT NULL,
    graph_version         BIGINT       NOT NULL,
    mapping_schema_version INT          NOT NULL,
    entity_linking_version VARCHAR(100) NOT NULL,
    state                 VARCHAR(30)  NOT NULL DEFAULT 'QUEUED',
    stage                 VARCHAR(30)  NOT NULL DEFAULT 'QUEUED',
    source_manifest_id    BIGINT       NULL,
    content_epoch         BIGINT       NOT NULL DEFAULT 0,
    security_epoch        BIGINT       NOT NULL DEFAULT 0,
    event_watermark      BIGINT       NOT NULL DEFAULT 0,
    fencing_token         BIGINT       NOT NULL DEFAULT 0,
    lease_owner           VARCHAR(100) NULL,
    lease_expires_at      DATETIME(6)  NULL,
    started_at            DATETIME(6)  NULL,
    completed_at          DATETIME(6)  NULL,
    entity_count          BIGINT       NOT NULL DEFAULT 0,
    relation_count        BIGINT       NOT NULL DEFAULT 0,
    source_count          BIGINT       NOT NULL DEFAULT 0,
    community_count       BIGINT       NOT NULL DEFAULT 0,
    error_code             VARCHAR(200) NULL,
    error_summary         VARCHAR(1000) NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version          BIGINT       NOT NULL DEFAULT 0,
    active_kb_id          BIGINT GENERATED ALWAYS AS
        (CASE WHEN state IN ('QUEUED', 'EXTRACTING', 'PROJECTING', 'CLUSTERING',
                             'SUMMARIZING', 'INDEXING', 'VALIDATING', 'WAITING_FOR_CHUNKS',
                             'UNSUPPORTED', 'NEEDS_ATTENTION') THEN kb_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_graph_build_run_kb_graph UNIQUE (kb_id, graph_version),
    CONSTRAINT uk_graph_build_run_active_kb UNIQUE (active_kb_id),
    CONSTRAINT fk_graph_build_run_batch FOREIGN KEY (batch_id) REFERENCES graph_build_batch (id),
    CONSTRAINT fk_graph_build_run_community_version FOREIGN KEY (community_index_version)
        REFERENCES community_index_version (version_number),
    CONSTRAINT ck_graph_build_run_state CHECK
        (state IN ('QUEUED', 'EXTRACTING', 'PROJECTING', 'CLUSTERING', 'SUMMARIZING',
                   'INDEXING', 'VALIDATING', 'READY', 'PUBLISHED', 'FAILED', 'CANCELLED', 'STALE',
                   'WAITING_FOR_CHUNKS', 'UNSUPPORTED', 'NEEDS_ATTENTION')),
    CONSTRAINT ck_graph_build_run_stage CHECK
        (stage IN ('QUEUED', 'EXTRACTING', 'PROJECTING', 'CLUSTERING', 'SUMMARIZING',
                   'INDEXING', 'VALIDATING', 'READY', 'PUBLISHED', 'CLEANING'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_graph_build_run_batch ON graph_build_run (batch_id, id);
CREATE INDEX idx_graph_build_run_active ON graph_build_run (kb_id, state, id);

CREATE TABLE graph_source_manifest (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    run_id                BIGINT       NOT NULL,
    kb_id                 BIGINT       NOT NULL,
    manifest_hash         CHAR(64)     NOT NULL,
    content_epoch         BIGINT       NOT NULL,
    security_epoch        BIGINT       NOT NULL,
    event_watermark       BIGINT       NOT NULL,
    entry_count           BIGINT       NOT NULL DEFAULT 0,
    state                 VARCHAR(20)  NOT NULL DEFAULT 'CAPTURING',
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at          DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_graph_source_manifest_run UNIQUE (run_id),
    CONSTRAINT fk_graph_source_manifest_run FOREIGN KEY (run_id) REFERENCES graph_build_run (id),
    CONSTRAINT ck_graph_source_manifest_state CHECK (state IN ('CAPTURING', 'COMPLETE', 'STALE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE graph_build_run
    ADD CONSTRAINT fk_graph_build_run_manifest FOREIGN KEY (source_manifest_id)
        REFERENCES graph_source_manifest (id);

CREATE TABLE graph_source_manifest_entry (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    manifest_id           BIGINT       NOT NULL,
    source_chunk_id       VARCHAR(80)  NOT NULL,
    resource_type         VARCHAR(30)  NOT NULL,
    resource_id           BIGINT       NOT NULL,
    revision_id           BIGINT       NULL,
    lifecycle_version     BIGINT       NOT NULL,
    chunk_key             VARCHAR(255) NOT NULL,
    content_hash          CHAR(64)     NOT NULL,
    parser_version        VARCHAR(60)  NOT NULL,
    chunker_version       VARCHAR(60)  NOT NULL,
    chunk_index_version   INT          NOT NULL,
    entity_linking_version VARCHAR(100) NOT NULL,
    chunk_state            VARCHAR(30)  NOT NULL DEFAULT 'WAITING_FOR_CHUNKS',
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_graph_manifest_entry_source UNIQUE (manifest_id, source_chunk_id),
    CONSTRAINT fk_graph_manifest_entry_manifest FOREIGN KEY (manifest_id)
        REFERENCES graph_source_manifest (id),
    CONSTRAINT ck_graph_manifest_entry_state CHECK
        (chunk_state IN ('WAITING_FOR_CHUNKS', 'READY', 'MISSING', 'STALE', 'UNSUPPORTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_graph_manifest_entry_resource
    ON graph_source_manifest_entry (manifest_id, resource_type, resource_id, lifecycle_version);

CREATE TABLE graph_snapshot (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    run_id                BIGINT       NOT NULL,
    kb_id                 BIGINT       NOT NULL,
    graph_version         BIGINT       NOT NULL,
    chunk_index_version   INT          NOT NULL,
    chunk_physical_index  VARCHAR(100) NOT NULL,
    community_index_version BIGINT     NOT NULL,
    community_physical_index VARCHAR(160) NOT NULL,
    source_manifest_id    BIGINT       NOT NULL,
    source_manifest_hash  CHAR(64)     NOT NULL,
    entity_linking_version VARCHAR(100) NOT NULL,
    graph_schema_version  INT          NOT NULL,
    mapping_schema_version INT          NOT NULL,
    algorithm_mode        VARCHAR(50)  NOT NULL,
    engine_version        VARCHAR(100) NOT NULL,
    summary_model         VARCHAR(100) NULL,
    summary_prompt_version VARCHAR(100) NULL,
    embedding_model       VARCHAR(100) NULL,
    embedding_dimensions  INT          NULL,
    content_epoch         BIGINT       NOT NULL,
    security_epoch        BIGINT       NOT NULL,
    state                 VARCHAR(20)  NOT NULL DEFAULT 'BUILDING',
    entity_count          BIGINT       NOT NULL DEFAULT 0,
    relation_count        BIGINT       NOT NULL DEFAULT 0,
    community_count       BIGINT       NOT NULL DEFAULT 0,
    validation_json       MEDIUMTEXT   NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sealed_at             DATETIME(6)  NULL,
    retired_at            DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_graph_snapshot_identity UNIQUE (kb_id, graph_version),
    CONSTRAINT fk_graph_snapshot_run FOREIGN KEY (run_id) REFERENCES graph_build_run (id),
    CONSTRAINT fk_graph_snapshot_manifest FOREIGN KEY (source_manifest_id)
        REFERENCES graph_source_manifest (id),
    CONSTRAINT fk_graph_snapshot_community_version FOREIGN KEY (community_index_version)
        REFERENCES community_index_version (version_number),
    CONSTRAINT ck_graph_snapshot_state CHECK
        (state IN ('BUILDING', 'READY', 'PUBLISHED', 'RETIRED', 'DELETING', 'FAILED')),
    CONSTRAINT ck_graph_snapshot_algorithm CHECK (algorithm_mode = 'ARCADEDB_NATIVE_UNWEIGHTED'),
    CONSTRAINT ck_graph_snapshot_dimensions CHECK
        (embedding_dimensions IS NULL OR embedding_dimensions BETWEEN 64 AND 2048)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE graph_publication (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    kb_id                 BIGINT       NOT NULL,
    chunk_index_version   INT          NOT NULL,
    active_snapshot_id    BIGINT       NULL,
    expected_snapshot_id  BIGINT       NULL,
    content_epoch         BIGINT       NOT NULL DEFAULT 0,
    security_epoch        BIGINT       NOT NULL DEFAULT 0,
    published_at          DATETIME(6)  NULL,
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version          BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_graph_publication_pair UNIQUE (kb_id, chunk_index_version),
    CONSTRAINT fk_graph_publication_active FOREIGN KEY (active_snapshot_id) REFERENCES graph_snapshot (id),
    CONSTRAINT fk_graph_publication_expected FOREIGN KEY (expected_snapshot_id) REFERENCES graph_snapshot (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE graph_resource_reference (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    snapshot_id           BIGINT       NOT NULL,
    resource_kind         VARCHAR(30)  NOT NULL,
    resource_identity     VARCHAR(255) NOT NULL,
    cleanup_state         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    last_error_summary    VARCHAR(1000) NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at            DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_graph_resource_reference UNIQUE (snapshot_id, resource_kind, resource_identity),
    CONSTRAINT fk_graph_resource_reference_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES graph_snapshot (id),
    CONSTRAINT ck_graph_resource_reference_state CHECK
        (cleanup_state IN ('ACTIVE', 'DELETING', 'DELETED', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE graph_build_audit (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id              BIGINT       NULL,
    run_id                BIGINT       NULL,
    action                VARCHAR(40)  NOT NULL,
    idempotency_key       VARCHAR(240) NULL,
    operator              VARCHAR(100) NOT NULL,
    result_state          VARCHAR(40)  NULL,
    summary               VARCHAR(1000) NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_graph_build_audit_batch FOREIGN KEY (batch_id) REFERENCES graph_build_batch (id),
    CONSTRAINT fk_graph_build_audit_run FOREIGN KEY (run_id) REFERENCES graph_build_run (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_graph_build_audit_time ON graph_build_audit (created_at, id);
