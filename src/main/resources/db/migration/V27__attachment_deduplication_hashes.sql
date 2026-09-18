-- Browser-computed fingerprints. Existing test attachments are intentionally left
-- without fingerprints; callers may delete and re-upload them.
CREATE TABLE attachment_blob (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    hash_version          VARCHAR(32)  NOT NULL,
    prefix_sha256         BINARY(32)   NOT NULL,
    full_sha256           BINARY(32)   NOT NULL,
    byte_size             BIGINT       NOT NULL,
    content_type          VARCHAR(100) NOT NULL,
    content_center_file_id BIGINT      NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_attachment_blob_full UNIQUE (hash_version, full_sha256, byte_size),
    CONSTRAINT ck_attachment_blob_size CHECK (byte_size > 0),
    CONSTRAINT ck_attachment_blob_status CHECK (status IN ('PENDING', 'STORED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_attachment_blob_prefix
    ON attachment_blob (hash_version, prefix_sha256, byte_size, status);

ALTER TABLE attachment
    ADD COLUMN blob_id BIGINT NULL AFTER byte_size,
    ADD CONSTRAINT fk_attachment_blob FOREIGN KEY (blob_id) REFERENCES attachment_blob (id),
    ADD INDEX idx_attachment_blob_id (blob_id),
    DROP KEY uk_attachment_content_center_file_id;
