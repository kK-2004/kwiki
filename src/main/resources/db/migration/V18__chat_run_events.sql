-- V18: durable SSE event log per chat run for activity replay. Events are the
-- exact wire payloads already delivered to the browser; replay re-sends them
-- in seq order. Old runs without stored events keep rendering from the
-- persisted assistant message only.

CREATE TABLE chat_run_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    run_request_id CHAR(36)  NOT NULL,
    session_id  BIGINT       NULL,
    seq         BIGINT       NOT NULL,
    event_type  VARCHAR(40)  NOT NULL,
    payload_json JSON        NOT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_run_event UNIQUE (run_request_id, seq),
    CONSTRAINT fk_chat_run_event_session FOREIGN KEY (session_id) REFERENCES chat_session (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_chat_run_event_session ON chat_run_event (session_id, run_request_id, seq);
