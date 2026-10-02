-- 字段格式插件化：结构版本整数改为插件 id。
-- 3 的字段保持一致；其余历史值无插件认领，运行时不受支持，只能清理。
ALTER TABLE search_index_version ADD COLUMN index_schema VARCHAR(64) NULL AFTER embedding_dimensions;
UPDATE search_index_version
SET index_schema = CASE mapping_schema_version
    WHEN 3 THEN 'kwiki-schema-1'
    ELSE CONCAT('legacy-schema-', mapping_schema_version) END;
ALTER TABLE search_index_version MODIFY COLUMN index_schema VARCHAR(64) NOT NULL;
ALTER TABLE search_index_version DROP COLUMN mapping_schema_version;

ALTER TABLE graph_build_run ADD COLUMN chunk_index_schema VARCHAR(64) NULL;
UPDATE graph_build_run
SET chunk_index_schema = CASE mapping_schema_version
    WHEN 3 THEN 'kwiki-schema-1'
    ELSE CONCAT('legacy-schema-', mapping_schema_version) END;
ALTER TABLE graph_build_run MODIFY COLUMN chunk_index_schema VARCHAR(64) NOT NULL;
ALTER TABLE graph_build_run DROP COLUMN mapping_schema_version;

ALTER TABLE graph_snapshot ADD COLUMN chunk_index_schema VARCHAR(64) NULL;
UPDATE graph_snapshot
SET chunk_index_schema = CASE mapping_schema_version
    WHEN 3 THEN 'kwiki-schema-1'
    ELSE CONCAT('legacy-schema-', mapping_schema_version) END;
ALTER TABLE graph_snapshot MODIFY COLUMN chunk_index_schema VARCHAR(64) NOT NULL;
ALTER TABLE graph_snapshot DROP COLUMN mapping_schema_version;

