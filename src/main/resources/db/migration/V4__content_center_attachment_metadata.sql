-- V4: replace the MinIO object locator with the content-center file identity.
-- Attachment content lives in the content center (k-File); kwiki persists only the
-- authoritative content-center fileId returned by the SDK upload. storageKey and
-- source stay internal to the content center and are never stored here.
--
-- The attachment table held no data when this migration was authored, so there is
-- no locator backfill and no compatibility column: the migration is forward-only
-- and rollback to the MinIO-backed schema is unsupported (restore from backup
-- instead). PENDING rows keep a NULL file id until a validated upload completes;
-- MySQL UNIQUE indexes treat NULLs as distinct, so the single unique index enforces
-- non-null uniqueness without blocking pending uploads.

ALTER TABLE attachment
    DROP KEY uk_attachment_object_key,
    DROP COLUMN object_key,
    ADD COLUMN content_center_file_id BIGINT NULL AFTER byte_size,
    ADD UNIQUE KEY uk_attachment_content_center_file_id (content_center_file_id);
