-- 图抽取身份、持久化派生结果以及来源 epoch。
-- 本迁移只追加，不修改历史 Flyway 文件。
CREATE TABLE graph_source_epoch (
    kb_id             BIGINT      NOT NULL,
    content_epoch     BIGINT      NOT NULL DEFAULT 0,
    security_epoch    BIGINT      NOT NULL DEFAULT 0,
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version      BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (kb_id),
    CONSTRAINT ck_graph_source_epoch_non_negative CHECK (content_epoch >= 0 AND security_epoch >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE graph_entity_registry (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    kb_id             BIGINT       NOT NULL,
    entity_id         VARCHAR(160) NOT NULL,
    canonical_name    VARCHAR(500) NOT NULL,
    entity_type       VARCHAR(40)  NOT NULL,
    aliases_json      JSON         NOT NULL,
    context_hash      CHAR(64)     NOT NULL,
    resolver_version  VARCHAR(100) NOT NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version      BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_graph_entity_registry_identity UNIQUE (kb_id, entity_id),
    CONSTRAINT uk_graph_entity_registry_resolution UNIQUE
        (kb_id, canonical_name, entity_type, context_hash, resolver_version),
    CONSTRAINT ck_graph_entity_registry_kb CHECK (kb_id > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_graph_entity_registry_name
    ON graph_entity_registry (kb_id, entity_type, canonical_name);

CREATE TABLE graph_extraction_result (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    kb_id                 BIGINT       NOT NULL,
    resource_type         VARCHAR(30)  NOT NULL,
    resource_id           BIGINT       NOT NULL,
    revision_id           BIGINT       NULL,
    lifecycle_version     BIGINT       NOT NULL,
    index_version         INT          NOT NULL,
    parser_version        VARCHAR(60)  NOT NULL,
    chunker_version       VARCHAR(60)  NOT NULL,
    chunk_key             VARCHAR(255) NOT NULL,
    source_chunk_id       VARCHAR(80)  NOT NULL,
    content_hash          CHAR(64)     NOT NULL,
    extractor_version     VARCHAR(100) NOT NULL,
    prompt_version        VARCHAR(100) NOT NULL,
    entity_linking_version VARCHAR(100) NOT NULL,
    content_epoch         BIGINT       NOT NULL DEFAULT 0,
    security_epoch        BIGINT       NOT NULL DEFAULT 0,
    entities_json         MEDIUMTEXT   NOT NULL,
    relations_json        MEDIUMTEXT   NOT NULL,
    state                 VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempt_count         INT          NOT NULL DEFAULT 0,
    last_error_class      VARCHAR(200) NULL,
    last_error_summary    VARCHAR(1000) NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version          BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_graph_extraction_identity UNIQUE
        (source_chunk_id, extractor_version, prompt_version, entity_linking_version),
    CONSTRAINT ck_graph_extraction_state CHECK
        (state IN ('PENDING', 'READY', 'FAILED', 'STALE')),
    CONSTRAINT ck_graph_extraction_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_graph_extraction_epochs CHECK (content_epoch >= 0 AND security_epoch >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_graph_extraction_source
    ON graph_extraction_result (kb_id, resource_type, resource_id, lifecycle_version);
CREATE INDEX idx_graph_extraction_state
    ON graph_extraction_result (state, updated_at, id);

CREATE TABLE graph_extraction_target (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    extraction_id         BIGINT       NOT NULL,
    target_kind           VARCHAR(30)  NOT NULL,
    target_identity       VARCHAR(255) NOT NULL,
    idempotency_key       VARCHAR(300) NOT NULL,
    state                 VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts              INT          NOT NULL DEFAULT 0,
    max_attempts          INT          NOT NULL DEFAULT 8,
    next_attempt_at       DATETIME(6)  NULL,
    lease_owner           VARCHAR(100) NULL,
    lease_expires_at      DATETIME(6)  NULL,
    last_error_class      VARCHAR(200) NULL,
    last_error_summary    VARCHAR(1000) NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version          BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_graph_extraction_target_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_graph_extraction_target_result FOREIGN KEY (extraction_id)
        REFERENCES graph_extraction_result (id),
    CONSTRAINT ck_graph_extraction_target_kind CHECK (target_kind IN ('ARCADEDB', 'ELASTICSEARCH')),
    CONSTRAINT ck_graph_extraction_target_state CHECK
        (state IN ('PENDING', 'LEASED', 'RETRY_WAIT', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_graph_extraction_target_attempts CHECK (attempts >= 0 AND max_attempts >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_graph_extraction_target_claim
    ON graph_extraction_target (state, next_attempt_at, id);
CREATE INDEX idx_graph_extraction_target_result
    ON graph_extraction_target (extraction_id, target_kind);
