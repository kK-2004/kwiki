-- PDF 派生图片在来源 PDF 中首次出现的页码（从 1 开始）。
-- 建索引时写入，供检索片段直接跳转到源文件对应页；历史行为 NULL，重建索引后补齐。
ALTER TABLE derived_image_asset
    ADD COLUMN source_page INT NULL AFTER source_ref;
