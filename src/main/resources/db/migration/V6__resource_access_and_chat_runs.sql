-- V6: resource ownership/audience boundaries and durable chat runs.
-- This migration is forward-only. Existing creator columns remain historical;
-- owner_id is the current transfer-able creator right.

ALTER TABLE knowledge_base
    ADD COLUMN owner_id BIGINT NULL AFTER created_by,
    ADD COLUMN join_approval_required BOOLEAN NOT NULL DEFAULT TRUE AFTER status;

UPDATE knowledge_base SET owner_id = created_by WHERE owner_id IS NULL;

ALTER TABLE knowledge_base
    MODIFY COLUMN owner_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_knowledge_base_owner FOREIGN KEY (owner_id) REFERENCES app_user (id);

ALTER TABLE knowledge_base_member
    DROP CHECK ck_kb_member_role,
    MODIFY COLUMN role VARCHAR(10) NOT NULL,
    ADD CONSTRAINT ck_kb_member_role CHECK (role IN ('OWNER', 'ADMIN', 'EDITOR', 'VIEWER'));

INSERT INTO knowledge_base_member (kb_id, user_id, role, created_by)
SELECT kb.id, kb.owner_id, 'OWNER', kb.owner_id
FROM knowledge_base kb
LEFT JOIN knowledge_base_member member
  ON member.kb_id = kb.id AND member.user_id = kb.owner_id
WHERE member.id IS NULL;

ALTER TABLE wiki_page
    ADD COLUMN owner_id BIGINT NULL AFTER created_by,
    ADD COLUMN audience_mode VARCHAR(24) NOT NULL DEFAULT 'KB_MEMBERS' AFTER status;

UPDATE wiki_page SET owner_id = created_by WHERE owner_id IS NULL;

ALTER TABLE wiki_page
    MODIFY COLUMN owner_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_wiki_page_owner FOREIGN KEY (owner_id) REFERENCES app_user (id),
    ADD CONSTRAINT ck_wiki_page_audience CHECK (audience_mode IN ('PRIVATE', 'SELECTED_MEMBERS', 'KB_MEMBERS'));

CREATE TABLE wiki_page_member (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    page_id      BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    role         VARCHAR(10) NOT NULL,
    created_by   BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_wiki_page_member UNIQUE (page_id, user_id),
    CONSTRAINT fk_wiki_page_member_page FOREIGN KEY (page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_wiki_page_member_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_wiki_page_member_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT ck_wiki_page_member_role CHECK (role IN ('VIEWER', 'EDITOR', 'ADMIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_wiki_page_member_user ON wiki_page_member (user_id, page_id);

CREATE TABLE wiki_page_audience_member (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    page_id        BIGINT      NOT NULL,
    source_kb_id   BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL,
    created_by     BIGINT      NOT NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_wiki_page_audience_member UNIQUE (page_id, source_kb_id, user_id),
    CONSTRAINT fk_wiki_page_audience_page FOREIGN KEY (page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_wiki_page_audience_kb FOREIGN KEY (source_kb_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_wiki_page_audience_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_wiki_page_audience_creator FOREIGN KEY (created_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_wiki_page_audience_user ON wiki_page_audience_member (user_id, page_id);
CREATE INDEX idx_wiki_page_audience_source ON wiki_page_audience_member (source_kb_id, page_id);

CREATE TABLE chat_run (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    uuid               CHAR(36)     NOT NULL,
    session_id         BIGINT       NOT NULL,
    user_id            BIGINT       NOT NULL,
    client_message_id  CHAR(36)     NOT NULL,
    request_id         CHAR(36)     NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    final_seq           BIGINT       NULL,
    public_progress    JSON         NULL,
    started_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at        DATETIME(6)  NULL,
    error_code         VARCHAR(80)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_run_uuid UNIQUE (uuid),
    CONSTRAINT uk_chat_run_client_message UNIQUE (session_id, client_message_id),
    CONSTRAINT uk_chat_run_request UNIQUE (request_id),
    CONSTRAINT fk_chat_run_session FOREIGN KEY (session_id) REFERENCES chat_session (id),
    CONSTRAINT fk_chat_run_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_chat_run_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_chat_run_session_status ON chat_run (session_id, status, started_at);

CREATE TABLE management_audit (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    actor_id       BIGINT       NOT NULL,
    resource_type  VARCHAR(20)  NOT NULL,
    resource_id    BIGINT       NOT NULL,
    action         VARCHAR(40)  NOT NULL,
    target_user_id BIGINT       NULL,
    details_json   JSON         NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_management_audit_actor FOREIGN KEY (actor_id) REFERENCES app_user (id),
    CONSTRAINT fk_management_audit_target FOREIGN KEY (target_user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_management_audit_resource ON management_audit (resource_type, resource_id, created_at);
