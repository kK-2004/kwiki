-- V8: permission-scoped @ mentions and durable in-app notifications.

CREATE TABLE comment_mention (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    comment_id   BIGINT      NOT NULL,
    recipient_id BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_comment_mention UNIQUE (comment_id, recipient_id),
    CONSTRAINT fk_comment_mention_comment FOREIGN KEY (comment_id) REFERENCES wiki_comment (id),
    CONSTRAINT fk_comment_mention_recipient FOREIGN KEY (recipient_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_comment_mention_recipient ON comment_mention (recipient_id, created_at);

CREATE TABLE notification (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    recipient_id BIGINT       NOT NULL,
    type         VARCHAR(32)  NOT NULL,
    page_id      BIGINT       NULL,
    comment_id   BIGINT       NULL,
    anchor_id    BIGINT       NULL,
    read_at      DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_mention UNIQUE (recipient_id, type, comment_id),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_id) REFERENCES app_user (id),
    CONSTRAINT fk_notification_page FOREIGN KEY (page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_notification_comment FOREIGN KEY (comment_id) REFERENCES wiki_comment (id),
    CONSTRAINT fk_notification_anchor FOREIGN KEY (anchor_id) REFERENCES selection_anchor (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_notification_recipient_created ON notification (recipient_id, created_at, id);
CREATE INDEX idx_notification_unread ON notification (recipient_id, read_at, created_at, id);
