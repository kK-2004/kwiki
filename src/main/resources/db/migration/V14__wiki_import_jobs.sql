-- V14: durable, idempotent text import lifecycle. The source attachment is kept
-- as provenance only; it is not independently enqueued for indexing.
ALTER TABLE attachment
    ADD COLUMN purpose VARCHAR(24) NOT NULL DEFAULT 'GENERAL' AFTER status,
    ADD CONSTRAINT ck_attachment_purpose CHECK (purpose IN ('GENERAL', 'WIKI_IMPORT_SOURCE'));

CREATE TABLE wiki_import_job (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    uuid                  CHAR(36)     NOT NULL,
    created_by            BIGINT       NOT NULL,
    kb_id                 BIGINT       NOT NULL,
    parent_id             BIGINT       NULL,
    source_attachment_id  BIGINT       NULL,
    idempotency_key       VARCHAR(120) NOT NULL,
    file_name             VARCHAR(300) NOT NULL,
    content_type          VARCHAR(100) NULL,
    audience_mode         VARCHAR(24)  NOT NULL,
    audience_members_json JSON         NOT NULL,
    state                 VARCHAR(24)  NOT NULL DEFAULT 'PENDING_UPLOAD',
    page_id               BIGINT       NULL,
    revision_id           BIGINT       NULL,
    warnings_json         JSON         NOT NULL,
    attempt_count         INT          NOT NULL DEFAULT 0,
    lease_owner           VARCHAR(120) NULL,
    lease_expires_at      DATETIME(6)  NULL,
    last_error            VARCHAR(500) NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_wiki_import_uuid UNIQUE (uuid),
    CONSTRAINT uk_wiki_import_idempotency UNIQUE (created_by, idempotency_key),
    CONSTRAINT fk_wiki_import_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT fk_wiki_import_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_wiki_import_parent FOREIGN KEY (parent_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_wiki_import_attachment FOREIGN KEY (source_attachment_id) REFERENCES attachment (id),
    CONSTRAINT fk_wiki_import_page FOREIGN KEY (page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_wiki_import_revision FOREIGN KEY (revision_id) REFERENCES wiki_page_revision (id),
    CONSTRAINT ck_wiki_import_audience CHECK (audience_mode IN ('PRIVATE', 'SELECTED_MEMBERS', 'KB_MEMBERS')),
    CONSTRAINT ck_wiki_import_state CHECK (state IN ('PENDING_UPLOAD', 'STORED', 'PARSING', 'SUCCEEDED', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_wiki_import_creator ON wiki_import_job (created_by, updated_at, id);
CREATE INDEX idx_wiki_import_worker ON wiki_import_job (state, lease_expires_at, updated_at, id);
