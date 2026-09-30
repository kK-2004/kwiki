-- 写入会话起点：版本开启写入（双写）那一刻的变更事件水位 E。
-- 存量迁移只覆盖 E 之后没有再发生变更的资源，其余由双写负责。
ALTER TABLE search_index_version
    ADD COLUMN write_enabled_event_id BIGINT NULL AFTER write_enabled;

-- 已处于写入状态的存量版本以当前事件水位作为会话起点
UPDATE search_index_version
SET write_enabled_event_id = (SELECT COALESCE(MAX(id), 0) FROM search_index_change_event)
WHERE write_enabled = TRUE AND deleted_at IS NULL;
