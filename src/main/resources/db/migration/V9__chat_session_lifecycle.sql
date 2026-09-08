-- V9: session lifecycle and run/message linkage for durable conversations.
ALTER TABLE chat_session
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD COLUMN agent_id VARCHAR(100) NULL;

CREATE INDEX idx_chat_session_user_updated ON chat_session (user_id, deleted_at, updated_at, id);

ALTER TABLE chat_message
    ADD COLUMN run_id BIGINT NULL,
    ADD CONSTRAINT fk_chat_message_run FOREIGN KEY (run_id) REFERENCES chat_run (id);

CREATE INDEX idx_chat_message_run ON chat_message (run_id, created_at);
