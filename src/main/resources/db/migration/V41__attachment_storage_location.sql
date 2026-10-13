-- 记录附件在内容中心的真实存储位置（对象键 + 数据源），内容中心切换默认数据源后
-- 仍可按真实位置读取；历史数据没有记录，读取时回退到按 fileId 查找。
-- 内容中心确认文件不存在时登记丢失：附件写 content_missing_at，去重记录置为 MISSING，
-- 之后同内容的上传会真正重新上传，而不是复用已丢失的文件。
ALTER TABLE attachment
    ADD COLUMN storage_key VARCHAR(1024) NULL AFTER content_center_file_id,
    ADD COLUMN storage_source VARCHAR(255) NULL AFTER storage_key,
    ADD COLUMN content_missing_at DATETIME(6) NULL AFTER storage_source,
    ADD INDEX idx_attachment_content_center_file_id (content_center_file_id);

ALTER TABLE attachment_blob
    ADD COLUMN storage_key VARCHAR(1024) NULL AFTER content_center_file_id,
    ADD COLUMN storage_source VARCHAR(255) NULL AFTER storage_key;

ALTER TABLE attachment_blob DROP CHECK ck_attachment_blob_status;

ALTER TABLE attachment_blob
    ADD CONSTRAINT ck_attachment_blob_status CHECK (status IN ('PENDING', 'STORED', 'MISSING'));
