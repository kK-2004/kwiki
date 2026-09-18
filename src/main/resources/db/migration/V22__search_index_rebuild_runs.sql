-- V22: immutable rebuild runs. A run freezes configRevision and the build
-- manifest snapshot at start; per-resource fixed ID bounds/cursors live in
-- the child table. At most one active run per version is enforced by a
-- generated-column uniqueness constraint; leases + lock_version provide the
-- second line of defence for crash recovery.

CREATE TABLE search_index_rebuild_run (
    id                         BIGINT        NOT NULL AUTO_INCREMENT,
    version_number             INT           NOT NULL,
    build_generation           BIGINT        NOT NULL DEFAULT 1,
    run_kind                   VARCHAR(20)   NOT NULL,
    config_revision            BIGINT        NOT NULL,
    build_manifest             MEDIUMTEXT    NOT NULL,
    state                      VARCHAR(20)   NOT NULL DEFAULT 'RUNNING',
    build_start_event_id       BIGINT        NOT NULL DEFAULT 0,
    replay_event_id            BIGINT        NOT NULL DEFAULT 0,
    dual_write_start_event_id  BIGINT        NULL,
    catchup_barrier_event_id   BIGINT        NULL,
    switch_state               VARCHAR(20)   NOT NULL DEFAULT 'NONE',
    resources_scanned          BIGINT        NOT NULL DEFAULT 0,
    resources_succeeded        BIGINT        NOT NULL DEFAULT 0,
    resources_skipped          BIGINT        NOT NULL DEFAULT 0,
    resources_failed           BIGINT        NOT NULL DEFAULT 0,
    embedding_calls            BIGINT        NOT NULL DEFAULT 0,
    lease_owner                VARCHAR(100)  NULL,
    lease_expires_at           DATETIME(6)   NULL,
    pause_requested            TINYINT(1)    NOT NULL DEFAULT 0,
    cancel_requested           TINYINT(1)    NOT NULL DEFAULT 0,
    error_class                VARCHAR(200)  NULL,
    error_summary              VARCHAR(1000) NULL,
    requested_by               VARCHAR(100)  NOT NULL,
    started_at                 DATETIME(6)   NULL,
    completed_at               DATETIME(6)   NULL,
    created_at                 DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                 DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version               BIGINT        NOT NULL DEFAULT 0,
    active_version             INT           GENERATED ALWAYS AS (CASE WHEN state IN ('PENDING', 'RUNNING', 'PAUSED') THEN version_number ELSE NULL END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_search_index_active_run UNIQUE (active_version),
    CONSTRAINT fk_search_index_run_version FOREIGN KEY (version_number) REFERENCES search_index_version (version_number),
    CONSTRAINT ck_search_index_run_kind CHECK (run_kind IN ('INITIAL', 'MANUAL')),
    CONSTRAINT ck_search_index_run_state CHECK (state IN ('PENDING', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_search_index_switch_state CHECK (switch_state IN ('NONE', 'PREPARING', 'READY', 'FAILED')),
    CONSTRAINT ck_search_index_run_generation CHECK (build_generation >= 1),
    CONSTRAINT ck_search_index_run_revision CHECK (config_revision >= 1),
    CONSTRAINT ck_search_index_run_watermarks CHECK (replay_event_id >= build_start_event_id AND (dual_write_start_event_id IS NULL OR (dual_write_start_event_id >= replay_event_id AND dual_write_start_event_id >= build_start_event_id)) AND (catchup_barrier_event_id IS NULL OR (dual_write_start_event_id IS NOT NULL AND catchup_barrier_event_id >= dual_write_start_event_id)))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_search_index_run_version ON search_index_rebuild_run (version_number, id);

-- Fixed per-source-resource ID bounds captured at rebuild start plus the
-- scanning cursor. The "chunks rebuild range" is represented per resource
-- type; workers derive parent/child chunks deterministically per target.
CREATE TABLE search_index_rebuild_range (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    run_id           BIGINT      NOT NULL,
    resource_type    VARCHAR(30) NOT NULL,
    min_id           BIGINT      NOT NULL,
    max_id           BIGINT      NOT NULL,
    last_seen_id     BIGINT      NOT NULL,
    tail_last_seen_id BIGINT     NOT NULL,
    tail_max_id      BIGINT      NULL,
    items_scanned    BIGINT      NOT NULL DEFAULT 0,
    items_succeeded  BIGINT      NOT NULL DEFAULT 0,
    items_skipped    BIGINT      NOT NULL DEFAULT 0,
    items_failed     BIGINT      NOT NULL DEFAULT 0,
    updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version     BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_search_index_range UNIQUE (run_id, resource_type),
    CONSTRAINT fk_search_index_range_run FOREIGN KEY (run_id) REFERENCES search_index_rebuild_run (id),
    CONSTRAINT ck_search_index_range_bounds CHECK (min_id >= 0 AND max_id >= min_id AND last_seen_id BETWEEN min_id - 1 AND max_id AND tail_last_seen_id >= min_id - 1 AND (tail_max_id IS NULL OR tail_max_id >= max_id))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
