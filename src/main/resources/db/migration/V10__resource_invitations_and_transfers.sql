-- V10: hashed resource invitations, approval requests, and ownership transfers.
CREATE TABLE resource_invitation (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    resource_type VARCHAR(10)  NOT NULL,
    resource_id   BIGINT       NOT NULL,
    token_hash    CHAR(64)     NOT NULL,
    issued_by     BIGINT       NOT NULL,
    role          VARCHAR(10)  NOT NULL,
    expires_at    DATETIME(6)  NOT NULL,
    revoked_at    DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_resource_invitation_hash UNIQUE (token_hash),
    CONSTRAINT ck_resource_invitation_type CHECK (resource_type IN ('KB', 'PAGE')),
    CONSTRAINT ck_resource_invitation_role CHECK (role IN ('VIEWER', 'EDITOR')),
    CONSTRAINT fk_resource_invitation_issuer FOREIGN KEY (issued_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_resource_invitation_resource ON resource_invitation (resource_type, resource_id, expires_at, revoked_at);

CREATE TABLE resource_join_request (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    invitation_id BIGINT       NOT NULL,
    resource_type VARCHAR(10)  NOT NULL,
    resource_id   BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    role          VARCHAR(10)  NOT NULL,
    status        VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    reviewed_by   BIGINT       NULL,
    reviewed_at   DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_resource_join_request UNIQUE (invitation_id, user_id),
    CONSTRAINT ck_resource_join_type CHECK (resource_type IN ('KB', 'PAGE')),
    CONSTRAINT ck_resource_join_role CHECK (role IN ('VIEWER', 'EDITOR')),
    CONSTRAINT ck_resource_join_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT fk_resource_join_invitation FOREIGN KEY (invitation_id) REFERENCES resource_invitation (id),
    CONSTRAINT fk_resource_join_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_resource_join_reviewer FOREIGN KEY (reviewed_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_resource_join_review ON resource_join_request (resource_type, resource_id, status, created_at);

CREATE TABLE ownership_transfer (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    resource_type VARCHAR(10)  NOT NULL,
    resource_id   BIGINT       NOT NULL,
    from_user_id  BIGINT       NOT NULL,
    to_user_id    BIGINT       NOT NULL,
    token_hash    CHAR(64)     NOT NULL,
    expires_at    DATETIME(6)  NOT NULL,
    status        VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    accepted_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ownership_transfer_hash UNIQUE (token_hash),
    CONSTRAINT ck_ownership_transfer_type CHECK (resource_type IN ('KB', 'PAGE')),
    CONSTRAINT ck_ownership_transfer_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT fk_ownership_transfer_from FOREIGN KEY (from_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_ownership_transfer_to FOREIGN KEY (to_user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_ownership_transfer_resource ON ownership_transfer (resource_type, resource_id, status, expires_at);
