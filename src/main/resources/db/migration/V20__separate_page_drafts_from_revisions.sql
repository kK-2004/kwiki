-- Drafts are mutable working copies. Only publishing creates an immutable
-- numbered revision, so repeated saves do not pollute version history.
CREATE TABLE wiki_page_draft (
    page_id      BIGINT       NOT NULL,
    markdown     MEDIUMTEXT   NOT NULL,
    plain_text   MEDIUMTEXT   NOT NULL,
    change_note  VARCHAR(500) NULL,
    updated_by   BIGINT       NOT NULL,
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (page_id),
    CONSTRAINT fk_wiki_page_draft_page FOREIGN KEY (page_id) REFERENCES wiki_page (id),
    CONSTRAINT fk_wiki_page_draft_author FOREIGN KEY (updated_by) REFERENCES app_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE wiki_page_revision
    ADD COLUMN published_at DATETIME(6) NULL AFTER created_at;

-- Preserve the latest unpublished working copy during the lifecycle upgrade.
INSERT INTO wiki_page_draft (page_id, markdown, plain_text, change_note, updated_by, updated_at)
SELECT p.id, d.markdown, d.plain_text, d.change_note, d.created_by, d.created_at
FROM wiki_page p
JOIN wiki_page_revision d ON d.id = p.current_draft_revision_id
WHERE p.current_published_revision_id IS NULL
   OR p.current_draft_revision_id <> p.current_published_revision_id;

-- Existing revisions up through the reader-visible pointer are known published
-- history. Later draft snapshots remain stored for rollback safety but stay
-- unmarked and therefore hidden from published history.
UPDATE wiki_page_revision r
JOIN wiki_page p ON p.id = r.page_id
JOIN wiki_page_revision published ON published.id = p.current_published_revision_id
SET r.published_at = r.created_at
WHERE r.revision_no <= published.revision_no;

UPDATE wiki_page
SET current_draft_revision_id = current_published_revision_id
WHERE current_draft_revision_id IS NULL
   OR current_published_revision_id IS NULL
   OR current_draft_revision_id <> current_published_revision_id;
