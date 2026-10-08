-- 路由状态与待答反问：下一轮路由读取上一轮判定；反问回答到达时校验待答记录。
ALTER TABLE chat_run
    ADD COLUMN route_intent VARCHAR(30) NULL AFTER error_code,
    ADD COLUMN route_source VARCHAR(20) NULL AFTER route_intent,
    ADD COLUMN route_needs_retrieval TINYINT(1) NULL AFTER route_source,
    ADD COLUMN route_confidence DOUBLE NULL AFTER route_needs_retrieval,
    ADD COLUMN pending_clarification JSON NULL AFTER route_confidence;
