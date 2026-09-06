-- V2: wiki pages, immutable revisions, links, tags, attachments, and sources.
-- wiki_page keeps parent + sibling ordering + draft/published revision pointers;
-- wiki_page_revision is append-only content history. Cycle safety and the
-- same-knowledge-base parent rule are enforced by WikiTreeService before every
-- move (MySQL CHECK constraints cannot contain subqueries).

CREATE TABLE wiki_page (
    id                            BIGINT       NOT NULL AUTO_INCREMENT,
    uuid                          CHAR(36)     NOT NULL,
    kb_id                         BIGINT       NOT NULL,
    parent_id                     BIGINT       NULL,
    title                         VARCHAR(300) NOT NULL,
    node_type                     VARCHAR(10)  NOT NULL DEFAULT 'PAGE',
    sibling_order                 INT          NOT NULL DEFAULT 0,
    status                        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    current_draft_revision_id     BIGINT       NULL,
    current_published_revision_id BIGINT       NULL,
    created_by                    BIGINT       NOT NULL,
    created_at                    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version                  BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_wiki_page_uuid UNIQUE (uuid),
    CONSTRAINT fk_wiki_page_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_wiki_page_parent FOREIGN KEY (parent_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_wiki_page_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT ck_wiki_page_node_type CHECK (node_type IN ('FOLDER', 'PAGE')),
    CONSTRAINT ck_wiki_page_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_wiki_page_title CHECK (CHAR_LENGTH(TRIM(title)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_wiki_page_tree ON wiki_page (kb_id, parent_id, sibling_order);

-- Append-only revision history; immutability is enforced by the service layer,
-- the unique (page_id, revision_no) keeps revision numbering collision-free.
CREATE TABLE wiki_page_revision (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    page_id      BIGINT       NOT NULL,
    revision_no  INT          NOT NULL,
    markdown     MEDIUMTEXT   NOT NULL,
    plain_text   MEDIUMTEXT   NOT NULL,
    change_note  VARCHAR(500) NULL,
    created_by   BIGINT       NOT NULL,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_page_revision_no UNIQUE (page_id, revision_no),
    CONSTRAINT fk_page_revision_page FOREIGN KEY (page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_page_revision_author FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT ck_page_revision_no CHECK (revision_no >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE wiki_page
    ADD CONSTRAINT fk_wiki_page_draft_revision
    FOREIGN KEY (current_draft_revision_id) REFERENCES wiki_page_revision (id),
    ADD CONSTRAINT fk_wiki_page_published_revision
    FOREIGN KEY (current_published_revision_id) REFERENCES wiki_page_revision (id);

-- Directed page-to-page links resolved to stable page ids from Markdown links.
CREATE TABLE wiki_link (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    source_page_id  BIGINT      NOT NULL,
    target_page_id  BIGINT      NOT NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_wiki_link UNIQUE (source_page_id, target_page_id),
    CONSTRAINT fk_wiki_link_source FOREIGN KEY (source_page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_wiki_link_target FOREIGN KEY (target_page_id) REFERENCES wiki_page (id),
    CONSTRAINT ck_wiki_link_no_self CHECK (source_page_id <> target_page_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_wiki_link_target ON wiki_link (target_page_id);

CREATE TABLE wiki_tag (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    kb_id      BIGINT      NOT NULL,
    name       VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_wiki_tag UNIQUE (kb_id, name),
    CONSTRAINT fk_wiki_tag_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base (id),
    CONSTRAINT ck_wiki_tag_name CHECK (CHAR_LENGTH(TRIM(name)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE wiki_page_tag (
    page_id    BIGINT      NOT NULL,
    tag_id     BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (page_id, tag_id),
    CONSTRAINT fk_page_tag_page FOREIGN KEY (page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_page_tag_tag FOREIGN KEY (tag_id) REFERENCES wiki_tag (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE attachment (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    uuid         CHAR(36)     NOT NULL,
    kb_id        BIGINT       NOT NULL,
    uploaded_by  BIGINT       NOT NULL,
    file_name    VARCHAR(300) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    byte_size    BIGINT       NOT NULL,
    object_key   VARCHAR(500) NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    lock_version BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_attachment_uuid UNIQUE (uuid),
    CONSTRAINT uk_attachment_object_key UNIQUE (object_key),
    CONSTRAINT fk_attachment_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base (id),
    CONSTRAINT fk_attachment_uploader FOREIGN KEY (uploaded_by) REFERENCES app_user (id),
    CONSTRAINT ck_attachment_status CHECK (status IN ('PENDING', 'STORED', 'ARCHIVED')),
    CONSTRAINT ck_attachment_size CHECK (byte_size >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Provenance: which uploaded document a page was derived from.
CREATE TABLE source_document (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    page_id       BIGINT      NOT NULL,
    attachment_id BIGINT      NOT NULL,
    relationship  VARCHAR(20) NOT NULL DEFAULT 'DERIVED_FROM',
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_source_document UNIQUE (page_id, attachment_id),
    CONSTRAINT fk_source_document_page FOREIGN KEY (page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_source_document_attachment FOREIGN KEY (attachment_id) REFERENCES attachment (id),
    CONSTRAINT ck_source_document_relationship CHECK (relationship IN ('DERIVED_FROM', 'UPLOADED_SOURCE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
