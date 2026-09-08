-- V12: user-scoped recent visits and generated summary projections.
CREATE TABLE recent_visit (
    user_id    BIGINT      NOT NULL,
    page_id    BIGINT      NOT NULL,
    visited_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, page_id),
    CONSTRAINT fk_recent_visit_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_recent_visit_page FOREIGN KEY (page_id) REFERENCES wiki_page (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_recent_visit_user_time ON recent_visit (user_id, visited_at, page_id);

CREATE TABLE page_summary (
    page_id       BIGINT       NOT NULL,
    revision_id   BIGINT       NOT NULL,
    summary_text  VARCHAR(2000) NOT NULL,
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (page_id),
    CONSTRAINT fk_page_summary_page FOREIGN KEY (page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_page_summary_revision FOREIGN KEY (revision_id) REFERENCES wiki_page_revision (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
