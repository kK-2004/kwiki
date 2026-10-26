-- 实体跨块合并：按归一化名称 + 类型在知识库内合并同一实体，不再以所在块正文区分。
-- 旧行 normalized_name 为 NULL，不参与新匹配（唯一键允许多个 NULL）；重建时按新规则重新登记。
ALTER TABLE graph_entity_registry
    ADD COLUMN normalized_name VARCHAR(500) NULL,
    ADD COLUMN context_snippet VARCHAR(500) NULL,
    ADD CONSTRAINT uk_graph_entity_registry_normalized
        UNIQUE (kb_id, normalized_name, entity_type, resolver_version);

-- 实体别名（归一化后）：别名命中或同名不同类型属于「有争议」的合并候选，交给判定。
CREATE TABLE graph_entity_alias (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    kb_id             BIGINT       NOT NULL,
    resolver_version  VARCHAR(100) NOT NULL,
    entity_id         VARCHAR(160) NOT NULL,
    normalized_alias  VARCHAR(255) NOT NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_graph_entity_alias UNIQUE (kb_id, resolver_version, normalized_alias, entity_id),
    INDEX idx_graph_entity_alias_entity (kb_id, entity_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 有争议合并的判定结果缓存：同一候选与同一已登记实体只判定一次（重建、重试复用）。
CREATE TABLE graph_entity_merge_decision (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    kb_id             BIGINT       NOT NULL,
    resolver_version  VARCHAR(100) NOT NULL,
    candidate_key     CHAR(64)     NOT NULL,
    entity_id         VARCHAR(160) NOT NULL,
    same_entity       TINYINT(1)   NOT NULL,
    judge             VARCHAR(200) NOT NULL,
    reason            VARCHAR(500) NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_graph_entity_merge_decision UNIQUE (kb_id, resolver_version, candidate_key, entity_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
