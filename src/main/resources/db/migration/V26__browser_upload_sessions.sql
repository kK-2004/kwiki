-- Temporary provider locators are server-owned; signed URLs and credentials are never persisted.
-- Completed attachments retain content_center_file_id; V27 may additionally
-- associate several attachment rows with one verified attachment_blob.
CREATE TABLE attachment_upload_session (
    attachment_id BIGINT NOT NULL,
    storage_key VARCHAR(1024) NOT NULL,
    source VARCHAR(255) NOT NULL,
    provider_file_id BIGINT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (attachment_id),
    CONSTRAINT fk_upload_session_attachment FOREIGN KEY (attachment_id)
        REFERENCES attachment (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
