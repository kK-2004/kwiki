-- 存量迁移 run：run_kind 新增 MIGRATION（写入开启后补入双写起点之前的历史数据）。
-- 保留 INITIAL / MANUAL 以兼容历史 run 行。
ALTER TABLE search_index_rebuild_run DROP CHECK ck_search_index_run_kind;

ALTER TABLE search_index_rebuild_run
    ADD CONSTRAINT ck_search_index_run_kind CHECK (run_kind IN ('INITIAL', 'MANUAL', 'MIGRATION'));
