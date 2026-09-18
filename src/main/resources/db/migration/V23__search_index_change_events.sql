-- V23: append-only monotonic change-event outbox plus per-target fan-out
-- execution state. Events are only ever INSERTed (updates/deletes are
-- forbidden by the access discipline enforced in the repository layer).
-- Target rows snapshot the physical index name at enqueue time so a later
-- alias switch can never retarget queued work; the idempotency key encodes
-- origin event + target version + operation.

CREATE TABLE search_index_change_event (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    resource_type     VARCHAR(30)  NOT NULL,
    resource_id       BIGINT       NOT NULL,
    revision_id       BIGINT       NULL,
    kb_id             BIGINT       NULL,
    operation         VARCHAR(10)  NOT NULL,
    lifecycle_version BIGINT       NOT NULL,
    origin            VARCHAR(20)  NOT NULL DEFAULT 'LIVE',
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_search_index_event_operation CHECK (operation IN ('UPSERT', 'DELETE', 'ARCHIVE', 'RESTORE')),
    CONSTRAINT ck_search_index_event_origin CHECK (origin IN ('LIVE', 'REBUILD', 'CATCHUP')),
    CONSTRAINT ck_search_index_event_lifecycle CHECK (lifecycle_version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_search_index_event_resource ON search_index_change_event (resource_type, resource_id, id);

CREATE TABLE indexing_job_target (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    job_id             BIGINT        NOT NULL,
    event_id           BIGINT        NULL,
    target_version     INT           NOT NULL,
    physical_name      VARCHAR(100)  NOT NULL,
    idempotency_key    VARCHAR(220)  NOT NULL,
    state              VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts           INT           NOT NULL DEFAULT 0,
    max_attempts       INT           NOT NULL DEFAULT 8,
    next_attempt_at    DATETIME(6)   NULL,
    lease_owner        VARCHAR(100)  NULL,
    lease_expires_at   DATETIME(6)   NULL,
    last_error_class   VARCHAR(200)  NULL,
    last_error_summary VARCHAR(1000) NULL,
    created_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version       BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_indexing_job_target_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_indexing_job_target_job FOREIGN KEY (job_id) REFERENCES indexing_job (id),
    CONSTRAINT fk_indexing_job_target_event FOREIGN KEY (event_id) REFERENCES search_index_change_event (id),
    CONSTRAINT ck_indexing_job_target_state CHECK (state IN ('PENDING', 'LEASED', 'RETRY_WAIT', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_indexing_job_target_attempts CHECK (attempts >= 0 AND max_attempts >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_indexing_job_target_claim ON indexing_job_target (target_version, state, next_attempt_at);
CREATE INDEX idx_indexing_job_target_job ON indexing_job_target (job_id);
