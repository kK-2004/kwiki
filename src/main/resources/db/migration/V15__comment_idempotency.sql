-- V15: retry-safe comment creation scoped to page and author.
ALTER TABLE wiki_comment
    ADD COLUMN idempotency_key VARCHAR(120) NULL AFTER anchor_id;

CREATE UNIQUE INDEX uk_wiki_comment_idempotency
    ON wiki_comment (page_id, author_id, idempotency_key);
