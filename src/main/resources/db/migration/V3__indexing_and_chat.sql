-- V3: durable indexing jobs (MySQL-backed work queue instead of Kafka), chat
-- sessions/messages, request traces, and per-knowledge-base scope versions.

CREATE TABLE indexing_job (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    job_type             VARCHAR(10)   NOT NULL,
    resource_type        VARCHAR(30)   NOT NULL,
    resource_id          BIGINT        NOT NULL,
    revision_id          BIGINT        NULL,
    idempotency_key      VARCHAR(200)  NOT NULL,
    state                VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts             INT           NOT NULL DEFAULT 0,
    max_attempts         INT           NOT NULL DEFAULT 8,
    next_attempt_at      DATETIME(6)   NULL,
    lease_owner          VARCHAR(100)  NULL,
    lease_expires_at     DATETIME(6)   NULL,
    last_error_class     VARCHAR(200)  NULL,
    last_error_summary   VARCHAR(1000) NULL,
    created_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version         BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_indexing_job_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_indexing_job_type CHECK (job_type IN ('UPSERT', 'DELETE')),
    CONSTRAINT ck_indexing_job_state CHECK (state IN ('PENDING', 'LEASED', 'RETRY_WAIT', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_indexing_job_attempts CHECK (attempts >= 0 AND max_attempts >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_indexing_job_claim ON indexing_job (state, next_attempt_at);
CREATE INDEX idx_indexing_job_resource ON indexing_job (resource_type, resource_id);

CREATE TABLE chat_session (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    uuid         CHAR(36)     NOT NULL,
    user_id      BIGINT       NOT NULL,
    title        VARCHAR(300) NULL,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_session_uuid UNIQUE (uuid),
    CONSTRAINT fk_chat_session_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_chat_session_user ON chat_session (user_id);

CREATE TABLE chat_message (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    session_id  BIGINT      NOT NULL,
    role        VARCHAR(20) NOT NULL,
    content     MEDIUMTEXT  NOT NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_message_session FOREIGN KEY (session_id) REFERENCES chat_session (id),
    CONSTRAINT ck_chat_message_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    CONSTRAINT ck_chat_message_content CHECK (CHAR_LENGTH(TRIM(content)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_chat_message_session ON chat_message (session_id, created_at);

-- Non-sensitive agent trace metadata for audit/diagnostics. Credential values and
-- unrestricted evidence must never be written here (enforced by writers + tests).
CREATE TABLE request_trace (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    correlation_id VARCHAR(64)  NOT NULL,
    user_id        BIGINT       NULL,
    kind           VARCHAR(30)  NOT NULL,
    trace_json     MEDIUMTEXT   NOT NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_request_trace_correlation UNIQUE (correlation_id, kind)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Monotonic per-knowledge-base scope version; membership changes bump it so
-- in-flight retrieval/generation under a stale scope can be aborted.
CREATE TABLE scope_version (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    kb_id      BIGINT      NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_scope_version_kb UNIQUE (kb_id),
    CONSTRAINT fk_scope_version_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base (id),
    CONSTRAINT ck_scope_version_positive CHECK (version >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
