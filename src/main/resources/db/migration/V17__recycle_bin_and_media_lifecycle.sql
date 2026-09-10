-- V17: recoverable recycle-bin batches, resource lifecycle versions, and
-- page-revision media references. Forward-only; existing ACTIVE/ARCHIVED rows
-- keep their status values. Legacy ARCHIVED rows without a reliable archive
-- timestamp are backfilled here with a full 7-day retention window starting at
-- this migration's execution time (origin=LEGACY_BACKFILL) — updatedAt is
-- never used to guess an earlier expiry.

ALTER TABLE wiki_page
    ADD COLUMN lifecycle_version BIGINT NOT NULL DEFAULT 1 AFTER status;

ALTER TABLE knowledge_base
    ADD COLUMN lifecycle_version BIGINT NOT NULL DEFAULT 1 AFTER status;

-- Index-job fencing: jobs may carry the lifecycle version observed when the
-- work was enqueued. The worker compares it with the current version and skips
-- work that a restore (version bump) has invalidated, so a delayed delete can
-- never remove a restored index and a delayed upsert can never revive archived
-- chunks. NULL means "no fencing" for legacy/internal producers.
ALTER TABLE indexing_job
    ADD COLUMN expected_lifecycle_version BIGINT NULL AFTER revision_id;

CREATE TABLE archive_batch (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    batch_uuid        CHAR(36)     NOT NULL,
    scope_type        VARCHAR(20)  NOT NULL,
    root_resource_id  BIGINT       NOT NULL,
    kb_id             BIGINT       NOT NULL,
    operator_id       BIGINT       NOT NULL,
    archived_at       DATETIME(6)  NOT NULL,
    purge_after       DATETIME(6)  NOT NULL,
    state             VARCHAR(20)  NOT NULL DEFAULT 'ARCHIVED',
    index_sync_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    origin            VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
    item_count        INT          NOT NULL DEFAULT 0,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version      BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_archive_batch_uuid UNIQUE (batch_uuid),
    CONSTRAINT fk_archive_batch_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_archive_batch_operator FOREIGN KEY (operator_id) REFERENCES app_user (id),
    CONSTRAINT ck_archive_batch_scope CHECK (scope_type IN ('PAGE', 'KNOWLEDGE_BASE')),
    CONSTRAINT ck_archive_batch_state CHECK (state IN ('ARCHIVED', 'RESTORED', 'PURGED')),
    CONSTRAINT ck_archive_batch_sync CHECK (index_sync_status IN ('PENDING', 'SYNCED')),
    CONSTRAINT ck_archive_batch_origin CHECK (origin IN ('NORMAL', 'LEGACY_BACKFILL')),
    CONSTRAINT ck_archive_batch_window CHECK (purge_after > archived_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_archive_batch_state_purge ON archive_batch (state, purge_after);
CREATE INDEX idx_archive_batch_kb ON archive_batch (kb_id, state, archived_at);

CREATE TABLE archive_batch_item (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    batch_id          BIGINT      NOT NULL,
    resource_type     VARCHAR(20) NOT NULL,
    resource_id       BIGINT      NOT NULL,
    kb_id             BIGINT      NOT NULL,
    prior_state       VARCHAR(20) NOT NULL,
    prior_parent_id   BIGINT      NULL,
    lifecycle_version BIGINT      NOT NULL DEFAULT 1,
    purged            BOOLEAN     NOT NULL DEFAULT FALSE,
    purged_at         DATETIME(6) NULL,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_archive_batch_item UNIQUE (batch_id, resource_type, resource_id),
    CONSTRAINT fk_archive_item_batch FOREIGN KEY (batch_id) REFERENCES archive_batch (id),
    CONSTRAINT ck_archive_item_type CHECK (resource_type IN ('PAGE', 'KNOWLEDGE_BASE', 'ATTACHMENT')),
    CONSTRAINT ck_archive_item_prior CHECK (prior_state IN ('ACTIVE', 'ARCHIVED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_archive_batch_item_resource ON archive_batch_item (resource_type, resource_id);
CREATE INDEX idx_archive_batch_item_kb ON archive_batch_item (kb_id, resource_type);

CREATE TABLE page_revision_media (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    revision_id   BIGINT      NOT NULL,
    page_id       BIGINT      NOT NULL,
    kb_id         BIGINT      NOT NULL,
    attachment_id BIGINT      NULL,
    media_kind    VARCHAR(10) NOT NULL,
    ref_kind      VARCHAR(12) NOT NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_page_revision_media UNIQUE (revision_id, media_kind, ref_kind, attachment_id),
    CONSTRAINT fk_page_revision_media_revision FOREIGN KEY (revision_id) REFERENCES wiki_page_revision (id),
    CONSTRAINT fk_page_revision_media_page FOREIGN KEY (page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_page_revision_media_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_page_revision_media_attachment FOREIGN KEY (attachment_id) REFERENCES attachment (id),
    CONSTRAINT ck_page_revision_media_kind CHECK (media_kind IN ('IMAGE', 'AUDIO', 'VIDEO')),
    CONSTRAINT ck_page_revision_media_ref CHECK (ref_kind IN ('ATTACHMENT', 'EXTERNAL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_page_revision_media_page ON page_revision_media (page_id, kb_id);
CREATE INDEX idx_page_revision_media_attachment ON page_revision_media (attachment_id);

-- Legacy backfill: one batch per already-archived root, full 7-day window from
-- this migration moment, operator falls back to the resource creator.
INSERT INTO archive_batch
    (batch_uuid, scope_type, root_resource_id, kb_id, operator_id,
     archived_at, purge_after, state, index_sync_status, origin, item_count)
SELECT
    UUID(), 'PAGE', p.id, p.kb_id, COALESCE(p.owner_id, p.created_by),
    NOW(6), DATE_ADD(NOW(6), INTERVAL 168 HOUR), 'ARCHIVED', 'PENDING', 'LEGACY_BACKFILL', 1
FROM wiki_page p
WHERE p.status = 'ARCHIVED'
  AND NOT EXISTS (
      SELECT 1 FROM archive_batch_item i
      WHERE i.resource_type = 'PAGE' AND i.resource_id = p.id AND i.batch_id IN
            (SELECT b.id FROM archive_batch b WHERE b.state <> 'PURGED'));

INSERT INTO archive_batch_item
    (batch_id, resource_type, resource_id, kb_id, prior_state, lifecycle_version)
SELECT b.id, 'PAGE', p.id, p.kb_id, 'ARCHIVED', p.lifecycle_version
FROM wiki_page p
JOIN archive_batch b
  ON b.scope_type = 'PAGE' AND b.root_resource_id = p.id AND b.origin = 'LEGACY_BACKFILL'
WHERE p.status = 'ARCHIVED'
  AND NOT EXISTS (
      SELECT 1 FROM archive_batch_item i
      WHERE i.batch_id = b.id AND i.resource_type = 'PAGE' AND i.resource_id = p.id);

INSERT INTO archive_batch
    (batch_uuid, scope_type, root_resource_id, kb_id, operator_id,
     archived_at, purge_after, state, index_sync_status, origin, item_count)
SELECT
    UUID(), 'KNOWLEDGE_BASE', kb.id, kb.id, COALESCE(kb.owner_id, kb.created_by),
    NOW(6), DATE_ADD(NOW(6), INTERVAL 168 HOUR), 'ARCHIVED', 'PENDING', 'LEGACY_BACKFILL', 1
FROM knowledge_base kb
WHERE kb.status = 'ARCHIVED'
  AND NOT EXISTS (
      SELECT 1 FROM archive_batch_item i
      WHERE i.resource_type = 'KNOWLEDGE_BASE' AND i.resource_id = kb.id);

INSERT INTO archive_batch_item
    (batch_id, resource_type, resource_id, kb_id, prior_state, lifecycle_version)
SELECT b.id, 'KNOWLEDGE_BASE', kb.id, kb.id, 'ARCHIVED', kb.lifecycle_version
FROM knowledge_base kb
JOIN archive_batch b
  ON b.scope_type = 'KNOWLEDGE_BASE' AND b.root_resource_id = kb.id
 AND b.origin = 'LEGACY_BACKFILL'
WHERE kb.status = 'ARCHIVED'
  AND NOT EXISTS (
      SELECT 1 FROM archive_batch_item i
      WHERE i.batch_id = b.id AND i.resource_type = 'KNOWLEDGE_BASE' AND i.resource_id = kb.id);
