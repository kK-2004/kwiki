-- V13: durable version/repair ledger for best-effort Redis counters.
CREATE TABLE stats_revision (
    page_id    BIGINT      NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (page_id),
    CONSTRAINT fk_stats_revision_page FOREIGN KEY (page_id) REFERENCES wiki_page (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE stats_repair (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    page_id      BIGINT      NOT NULL,
    reason       VARCHAR(80) NOT NULL,
    requested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_stats_repair_page FOREIGN KEY (page_id) REFERENCES wiki_page (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_stats_repair_pending ON stats_repair (completed_at, requested_at, id);
