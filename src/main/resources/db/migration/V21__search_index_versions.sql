-- V21: persistent physical search-index version registry (tombstoned,
-- monotonic version numbers; dirty is derived from config/built revisions,
-- never a stored boolean). Credentials never live here: the embedding
-- provider column stores the profile NAME only (e.g. "default").

CREATE TABLE search_index_version (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    version_number         INT          NOT NULL,
    physical_name          VARCHAR(100) NOT NULL,
    parser_version         VARCHAR(50)  NOT NULL,
    chunker_version        VARCHAR(50)  NOT NULL,
    embedding_provider     VARCHAR(100) NOT NULL,
    embedding_model        VARCHAR(100) NOT NULL,
    embedding_dimensions   INT          NOT NULL,
    mapping_schema_version INT          NOT NULL,
    config_revision        BIGINT       NOT NULL DEFAULT 1,
    built_config_revision  BIGINT       NULL,
    build_state            VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    catchup_status         VARCHAR(20)  NOT NULL DEFAULT 'BEHIND',
    write_enabled          TINYINT(1)   NOT NULL DEFAULT 0,
    admin_disabled         TINYINT(1)   NOT NULL DEFAULT 0,
    selected               TINYINT(1)   NOT NULL DEFAULT 0,
    pipeline_supported     TINYINT(1)   NOT NULL DEFAULT 1,
    mapping_hash           CHAR(64)     NULL,
    health_summary         VARCHAR(200) NULL,
    needs_attention_reason VARCHAR(500) NULL,
    last_validation_at     DATETIME(6)  NULL,
    validation_summary     MEDIUMTEXT   NULL,
    deleted_at             DATETIME(6)  NULL,
    created_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version           BIGINT       NOT NULL DEFAULT 0,
    selected_key           TINYINT      GENERATED ALWAYS AS (CASE WHEN selected = 1 THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_search_index_version_number UNIQUE (version_number),
    CONSTRAINT uk_search_index_version_name UNIQUE (physical_name),
    CONSTRAINT uk_search_index_single_selected UNIQUE (selected_key),
    CONSTRAINT ck_search_index_version_positive CHECK (version_number >= 1),
    CONSTRAINT ck_search_index_dimensions CHECK (embedding_dimensions BETWEEN 64 AND 2048),
    CONSTRAINT ck_search_index_config_revision CHECK (config_revision >= 1),
    CONSTRAINT ck_search_index_built_revision CHECK (built_config_revision IS NULL OR built_config_revision >= 1),
    CONSTRAINT ck_search_index_revision_order CHECK (built_config_revision IS NULL OR built_config_revision <= config_revision),
    CONSTRAINT ck_search_index_build_state CHECK (build_state IN ('NEW', 'BUILDING', 'BUILT', 'FAILED')),
    CONSTRAINT ck_search_index_catchup CHECK (catchup_status IN ('CURRENT', 'BEHIND'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_search_index_write_enabled ON search_index_version (write_enabled, deleted_at, version_number);
CREATE INDEX idx_search_index_admin_enabled ON search_index_version (admin_disabled, deleted_at, version_number);
CREATE INDEX idx_search_index_deleted ON search_index_version (deleted_at, version_number);
