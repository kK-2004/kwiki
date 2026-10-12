-- 模型目录：provider、模型、能力标签、按用途的模型分配与配置版本号。
-- API key 只以 AES-GCM 密文落库（api_key_cipher），后台展示使用掩码 api_key_hint。
CREATE TABLE model_provider (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    name               VARCHAR(100)  NOT NULL,
    protocol           VARCHAR(30)   NOT NULL DEFAULT 'openai_compatible',
    base_url           VARCHAR(500)  NOT NULL,
    api_key_cipher     VARCHAR(2000) NOT NULL,
    api_key_hint       VARCHAR(40)   NOT NULL,
    request_timeout_ms INT           NOT NULL DEFAULT 120000,
    max_retries        INT           NOT NULL DEFAULT 2,
    max_concurrency    INT           NULL,
    qps                DOUBLE        NULL,
    enabled            TINYINT(1)    NOT NULL DEFAULT 1,
    created_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_provider_name (name),
    CONSTRAINT ck_model_provider_timeout CHECK (request_timeout_ms BETWEEN 1000 AND 600000),
    CONSTRAINT ck_model_provider_retries CHECK (max_retries BETWEEN 0 AND 10),
    CONSTRAINT ck_model_provider_concurrency CHECK (max_concurrency IS NULL OR max_concurrency BETWEEN 1 AND 256),
    CONSTRAINT ck_model_provider_qps CHECK (qps IS NULL OR qps > 0)
);

CREATE TABLE model_definition (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    provider_id       BIGINT       NOT NULL,
    model_name        VARCHAR(200) NOT NULL,
    display_name      VARCHAR(200) NULL,
    dimensions        INT          NULL,
    max_output_tokens INT          NULL,
    supports_tools    TINYINT(1)   NOT NULL DEFAULT 1,
    supports_thinking TINYINT(1)   NOT NULL DEFAULT 0,
    enabled           TINYINT(1)   NOT NULL DEFAULT 1,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_definition_provider_name (provider_id, model_name),
    CONSTRAINT fk_model_definition_provider FOREIGN KEY (provider_id) REFERENCES model_provider (id),
    CONSTRAINT ck_model_definition_dimensions CHECK (dimensions IS NULL OR dimensions BETWEEN 64 AND 2048),
    CONSTRAINT ck_model_definition_output CHECK (max_output_tokens IS NULL OR max_output_tokens > 0)
);

CREATE TABLE model_definition_tag (
    model_id BIGINT      NOT NULL,
    tag      VARCHAR(20) NOT NULL,
    PRIMARY KEY (model_id, tag),
    CONSTRAINT fk_model_definition_tag_model FOREIGN KEY (model_id) REFERENCES model_definition (id) ON DELETE CASCADE,
    CONSTRAINT ck_model_definition_tag CHECK (tag IN ('chat', 'vision', 'embedding'))
);

CREATE TABLE model_assignment (
    purpose     VARCHAR(40) NOT NULL,
    model_id    BIGINT      NULL,
    temperature DOUBLE      NULL,
    max_tokens  INT         NULL,
    thinking    TINYINT(1)  NULL,
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (purpose),
    CONSTRAINT fk_model_assignment_model FOREIGN KEY (model_id) REFERENCES model_definition (id),
    CONSTRAINT ck_model_assignment_temperature CHECK (temperature IS NULL OR temperature BETWEEN 0 AND 2),
    CONSTRAINT ck_model_assignment_max_tokens CHECK (max_tokens IS NULL OR max_tokens > 0)
);

INSERT INTO model_assignment (purpose) VALUES
    ('AGENT_PLANNER'), ('RETRIEVAL_PLANNER'), ('ROUTER'), ('REWRITE'), ('DIRECT_ANSWER'),
    ('QUALITY_ANALYZER'), ('ANSWER'), ('SELECTION_QUESTION'), ('GRAPH_EXTRACTION'),
    ('GRAPH_SUMMARY'), ('VISION_SUMMARY');

-- 单行版本号：任何目录写入在同一事务内递增，各实例据此判断是否需要重新加载。
CREATE TABLE model_config_version (
    id      TINYINT NOT NULL,
    version BIGINT  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_model_config_version_singleton CHECK (id = 1)
);

INSERT INTO model_config_version (id, version) VALUES (1, 0);
