-- 模型与用途分配的扩展配置（JSON 对象，可空）：kwiki 认识的键（batchSize、timeoutMs）由 kwiki 解释，
-- 其余键作为请求体参数透传给 provider；用途分配上的同名键覆盖模型上的。
ALTER TABLE model_definition
    ADD COLUMN extra JSON NULL AFTER supports_thinking;

ALTER TABLE model_assignment
    ADD COLUMN extra JSON NULL AFTER thinking;
