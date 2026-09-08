-- V7: revision-aware selection anchors, two-level comment threads and idempotent interactions.

CREATE TABLE selection_anchor (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    page_id         BIGINT       NOT NULL,
    revision_id     BIGINT       NOT NULL,
    block_id        VARCHAR(128) NOT NULL,
    paragraph_hash  CHAR(64)     NOT NULL,
    start_offset    INT          NOT NULL,
    end_offset      INT          NOT NULL,
    quote           TEXT         NOT NULL,
    prefix_text     VARCHAR(300) NULL,
    suffix_text     VARCHAR(300) NULL,
    created_by      BIGINT       NOT NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_selection_anchor_page FOREIGN KEY (page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_selection_anchor_revision FOREIGN KEY (revision_id) REFERENCES wiki_page_revision (id),
    CONSTRAINT fk_selection_anchor_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT ck_selection_anchor_offsets CHECK (start_offset >= 0 AND end_offset > start_offset)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_selection_anchor_page_revision ON selection_anchor (page_id, revision_id, block_id);

CREATE TABLE wiki_comment (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    page_id     BIGINT       NOT NULL,
    author_id   BIGINT       NOT NULL,
    body        TEXT         NOT NULL,
    parent_id   BIGINT       NULL,
    reply_to    BIGINT       NULL,
    anchor_id   BIGINT       NULL,
    deleted_at  DATETIME(6)  NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_wiki_comment_page FOREIGN KEY (page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_wiki_comment_author FOREIGN KEY (author_id) REFERENCES app_user (id),
    CONSTRAINT fk_wiki_comment_parent FOREIGN KEY (parent_id) REFERENCES wiki_comment (id),
    CONSTRAINT fk_wiki_comment_reply_to FOREIGN KEY (reply_to) REFERENCES wiki_comment (id),
    CONSTRAINT fk_wiki_comment_anchor FOREIGN KEY (anchor_id) REFERENCES selection_anchor (id),
    CONSTRAINT ck_wiki_comment_body CHECK (CHAR_LENGTH(TRIM(body)) > 0),
    CONSTRAINT ck_wiki_comment_parent_reply CHECK (
        (parent_id IS NULL AND reply_to IS NULL) OR (parent_id IS NOT NULL AND reply_to IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_wiki_comment_page_root ON wiki_comment (page_id, parent_id, deleted_at, created_at, id);
CREATE INDEX idx_wiki_comment_parent ON wiki_comment (parent_id, deleted_at, created_at, id);

CREATE TABLE page_like (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    page_id    BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_page_like UNIQUE (page_id, user_id),
    CONSTRAINT fk_page_like_page FOREIGN KEY (page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_page_like_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE page_favorite (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    page_id    BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_page_favorite UNIQUE (page_id, user_id),
    CONSTRAINT fk_page_favorite_page FOREIGN KEY (page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_page_favorite_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE comment_like (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    comment_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_comment_like UNIQUE (comment_id, user_id),
    CONSTRAINT fk_comment_like_comment FOREIGN KEY (comment_id) REFERENCES wiki_comment (id),
    CONSTRAINT fk_comment_like_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_page_like_user ON page_like (user_id, created_at, page_id);
CREATE INDEX idx_page_favorite_user ON page_favorite (user_id, created_at, page_id);
CREATE INDEX idx_comment_like_comment ON comment_like (comment_id);
