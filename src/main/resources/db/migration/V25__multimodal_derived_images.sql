-- V25: durable identity for multimodal derived images (PDF embedded images,
-- mirrored external Markdown images) and their per (model, prompt-version)
-- vision summaries. The content-center contentId is the only persistent
-- external resource identity; no CDN URLs, credentials or markers here.
-- A separate asset/summary split lets prompt or model bumps reuse an
-- already-uploaded contentId while producing a new summary identity.

CREATE TABLE derived_image_asset (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    source_kind    VARCHAR(20)  NOT NULL,
    source_ref     VARCHAR(160) NOT NULL,
    source_kb_id   BIGINT       NOT NULL,
    parser_version VARCHAR(60)  NOT NULL,
    image_sha256   CHAR(64)     NOT NULL,
    content_id     BIGINT       NULL,
    content_type   VARCHAR(100) NULL,
    byte_size      BIGINT       NULL,
    state          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    cleanup_state  VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    source_url_hash CHAR(64)    NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version   BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_derived_image_asset_identity
        (source_kind, source_ref, parser_version, image_sha256),
    CONSTRAINT ck_derived_image_asset_kind CHECK (source_kind IN ('ATTACHMENT_PDF', 'ATTACHMENT_IMAGE', 'EXTERNAL_URL')),
    CONSTRAINT ck_derived_image_asset_state CHECK (state IN ('PENDING', 'UPLOADED', 'FAILED')),
    CONSTRAINT ck_derived_image_asset_cleanup CHECK (cleanup_state IN ('NONE', 'ORPHAN_CANDIDATE', 'CLEANUP_DEFERRED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_derived_image_asset_content ON derived_image_asset (content_id);
CREATE INDEX idx_derived_image_asset_kb ON derived_image_asset (source_kb_id, id);
CREATE INDEX idx_derived_image_asset_cleanup ON derived_image_asset (cleanup_state, id);

CREATE TABLE derived_image_summary (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    asset_id       BIGINT       NOT NULL,
    model          VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(40)  NOT NULL,
    state          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    summary        MEDIUMTEXT   NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version   BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_derived_image_summary_identity (asset_id, model, prompt_version),
    CONSTRAINT fk_derived_image_summary_asset FOREIGN KEY (asset_id) REFERENCES derived_image_asset (id),
    CONSTRAINT ck_derived_image_summary_state CHECK (state IN ('PENDING', 'READY', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
