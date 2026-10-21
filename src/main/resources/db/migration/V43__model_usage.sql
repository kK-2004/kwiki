-- 模型 token 用量：分钟级聚合（长期保留，供趋势图）与逐次调用明细（短期保留，供排查）。
-- 时间均为 UTC；user_id = 0 表示后台任务（系统）。
CREATE TABLE model_usage_minute (
    bucket_start   DATETIME     NOT NULL,
    source         VARCHAR(16)  NOT NULL,
    step           VARCHAR(32)  NOT NULL,
    provider       VARCHAR(128) NOT NULL,
    model          VARCHAR(128) NOT NULL,
    user_id        BIGINT       NOT NULL DEFAULT 0,
    calls          BIGINT       NOT NULL DEFAULT 0,
    failures       BIGINT       NOT NULL DEFAULT 0,
    input_tokens   BIGINT       NOT NULL DEFAULT 0,
    output_tokens  BIGINT       NOT NULL DEFAULT 0,
    latency_ms     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (bucket_start, source, step, provider, model, user_id),
    KEY idx_usage_minute_step (step, bucket_start),
    KEY idx_usage_minute_model (provider, model, bucket_start),
    KEY idx_usage_minute_user (user_id, bucket_start)
);

CREATE TABLE model_usage_call (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    occurred_at    DATETIME(3)  NOT NULL,
    source         VARCHAR(16)  NOT NULL,
    step           VARCHAR(32)  NOT NULL,
    provider       VARCHAR(128) NOT NULL,
    model          VARCHAR(128) NOT NULL,
    user_id        BIGINT       NOT NULL DEFAULT 0,
    request_id     VARCHAR(64)  NULL,
    input_tokens   INT          NOT NULL DEFAULT 0,
    output_tokens  INT          NOT NULL DEFAULT 0,
    latency_ms     INT          NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL,
    error_type     VARCHAR(128) NULL,
    KEY idx_usage_call_time (occurred_at),
    KEY idx_usage_call_request (request_id)
);
