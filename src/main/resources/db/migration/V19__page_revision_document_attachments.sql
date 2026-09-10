ALTER TABLE page_revision_media
    DROP CHECK ck_page_revision_media_kind;

ALTER TABLE page_revision_media
    MODIFY media_kind VARCHAR(16) NOT NULL,
    ADD CONSTRAINT ck_page_revision_media_kind
        CHECK (media_kind IN ('IMAGE', 'AUDIO', 'VIDEO', 'DOCUMENT'));
