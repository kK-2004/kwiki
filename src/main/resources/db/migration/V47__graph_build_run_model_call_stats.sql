-- 子任务模型调用统计：抽取 / 摘要调用失败后的重试次数与其中超时的次数。
-- 执行器每次失败尝试带隔离令牌累加；重试子任务时清零，只反映本次执行。
ALTER TABLE graph_build_run
    ADD COLUMN model_retries  BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN model_timeouts BIGINT NOT NULL DEFAULT 0;
