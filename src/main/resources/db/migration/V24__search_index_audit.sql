-- V24: sanitized administrator audit ledger for every index lifecycle
-- mutation, plus idempotency persistence so retried management requests
-- replay the original outcome instead of performing a second transition.
-- No credentials, document content or stack traces may reach these tables.

CREATE TABLE search_index_audit (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    action          VARCHAR(40)   NOT NULL,
    target_version  INT           NULL,
    run_id          BIGINT        NULL,
    operator        VARCHAR(100)  NOT NULL,
    config_revision BIGINT        NULL,
    prior_state     VARCHAR(300)  NULL,
    result_state    VARCHAR(300)  NULL,
    outcome         VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    error_summary   VARCHAR(1000) NULL,
    created_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_search_index_audit_outcome CHECK (outcome IN ('PENDING', 'SUCCESS', 'FAILURE', 'RECOVERED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_search_index_audit_time ON search_index_audit (created_at, id);
CREATE INDEX idx_search_index_audit_target ON search_index_audit (target_version, id);

CREATE TABLE search_index_idempotency (
    idempotency_key  VARCHAR(200)  NOT NULL,
    action           VARCHAR(40)   NOT NULL,
    target_version   INT           NULL,
    operator         VARCHAR(100)  NOT NULL,
    response_summary VARCHAR(1000) NULL,
    created_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (idempotency_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE search_index_validation_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version_number INT NOT NULL,
    run_id BIGINT NULL,
    config_revision BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    revision_valid TINYINT(1) NOT NULL,
    manifest_valid TINYINT(1) NOT NULL,
    mapping_valid TINYINT(1) NOT NULL,
    vector_dimension_valid TINYINT(1) NOT NULL,
    required_fields_valid TINYINT(1) NOT NULL,
    coverage_valid        TINYINT(1) NOT NULL,
    source_resources      BIGINT NOT NULL DEFAULT 0,
    missing_resources     BIGINT NOT NULL DEFAULT 0,
    integrity_valid       TINYINT(1) NOT NULL,
    stale_documents       BIGINT NOT NULL DEFAULT 0,
    orphan_children       BIGINT NOT NULL DEFAULT 0,
    malformed_vectors     BIGINT NOT NULL DEFAULT 0,
    mixed_manifest_documents BIGINT NOT NULL DEFAULT 0,
    synchronization_valid TINYINT(1) NOT NULL,
    smoke_queries_valid   TINYINT(1) NOT NULL,
    barrier_event_id      BIGINT NULL,
    cursor_fingerprint    VARCHAR(500) NULL,
    alias_fingerprint     VARCHAR(500) NULL,
    summary VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_search_validation_version FOREIGN KEY (version_number) REFERENCES search_index_version (version_number),
    CONSTRAINT fk_search_validation_run FOREIGN KEY (run_id) REFERENCES search_index_rebuild_run (id),
    CONSTRAINT ck_search_validation_status CHECK (status IN ('PASS', 'FAIL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_search_validation_version ON search_index_validation_report (version_number, id);
