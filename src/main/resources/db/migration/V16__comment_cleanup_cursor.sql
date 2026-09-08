-- V16: durable keyset cursor for comment cleanup retries and XXL-JOB handoff.
CREATE TABLE comment_cleanup_cursor (
    job_name   VARCHAR(80) NOT NULL,
    cursor_id  BIGINT      NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (job_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO comment_cleanup_cursor (job_name, cursor_id)
VALUES ('orphaned-replies', 0);
