-- V11: durable system agent definitions used by workspace navigation.
CREATE TABLE agent_definition (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    agent_key    VARCHAR(100) NOT NULL,
    name         VARCHAR(200) NOT NULL,
    description  VARCHAR(1000) NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_definition_key UNIQUE (agent_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO agent_definition (agent_key, name, description)
VALUES ('agentic-rag', 'Agentic RAG', '基于当前账号可访问知识的检索与问答');
