-- V33：索引灰度发布。
-- search_index_version_kb_scope：为索引版本登记知识库范围；没有任何行的版本即全局版本。
-- index_gray_release / index_gray_release_kb：灰度发布及其知识库；
-- active_kb_id 仅在灰度未结束时等于 kb_id，借唯一索引保证一个知识库同时只在一个进行中的灰度里。

CREATE TABLE search_index_version_kb_scope (
    version_number INT         NOT NULL,
    kb_id          BIGINT      NOT NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (version_number, kb_id),
    KEY idx_search_index_version_kb_scope_kb (kb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE index_gray_release (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    name                 VARCHAR(120)  NOT NULL,
    parser_version       VARCHAR(64)   NOT NULL,
    index_version_number INT           NOT NULL,
    status               VARCHAR(20)   NOT NULL,
    last_error           VARCHAR(1000) NULL,
    created_by           VARCHAR(100)  NOT NULL,
    created_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    switched_at          DATETIME(6)   NULL,
    ended_at             DATETIME(6)   NULL,
    updated_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_index_gray_release_version (index_version_number),
    KEY idx_index_gray_release_status (status),
    CONSTRAINT ck_index_gray_release_status
        CHECK (status IN ('CREATED', 'SYNCING', 'SYNCED', 'SWITCHED', 'ENDED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE index_gray_release_kb (
    release_id   BIGINT NOT NULL,
    kb_id        BIGINT NOT NULL,
    active_kb_id BIGINT NULL,
    PRIMARY KEY (release_id, kb_id),
    UNIQUE KEY uk_index_gray_release_active_kb (active_kb_id),
    CONSTRAINT fk_index_gray_release_kb_release
        FOREIGN KEY (release_id) REFERENCES index_gray_release (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
