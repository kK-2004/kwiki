-- V1: identity and knowledge base baseline.
-- Users, knowledge bases, members with OWNER/EDITOR/VIEWER roles, optimistic
-- lock versions, and audit columns shared by every kwiki table.

CREATE TABLE app_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    display_name  VARCHAR(128) NOT NULL,
    email         VARCHAR(254) NULL,
    is_admin      BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version  BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_username CHECK (CHAR_LENGTH(TRIM(username)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_base (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    uuid         CHAR(36)      NOT NULL,
    name         VARCHAR(200)  NOT NULL,
    description  VARCHAR(2000) NULL,
    status       VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_by   BIGINT        NOT NULL,
    created_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_knowledge_base_uuid UNIQUE (uuid),
    CONSTRAINT fk_knowledge_base_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT ck_knowledge_base_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_knowledge_base_name CHECK (CHAR_LENGTH(TRIM(name)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_base_member (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    kb_id        BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    role         VARCHAR(10) NOT NULL,
    created_by   BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_kb_member UNIQUE (kb_id, user_id),
    CONSTRAINT fk_kb_member_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_kb_member_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_kb_member_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT ck_kb_member_role CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_kb_member_user ON knowledge_base_member (user_id);
