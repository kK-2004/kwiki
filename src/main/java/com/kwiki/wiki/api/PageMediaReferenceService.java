package com.kwiki.wiki.api;

import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.render.MarkdownMediaScanner;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists the media references of a page revision (page_revision_media) in
 * the same transaction that saves the revision. Only stable
 * attachment://uuid references are recorded — external links are never
 * fetched or tracked. The rows drive authorized preview checks, archive
 * exclusivity decisions and physical cleanup ordering.
 */
@Service
public class PageMediaReferenceService {

    private final AttachmentRepository attachments;
    private final JdbcOperations jdbc;

    public PageMediaReferenceService(AttachmentRepository attachments,
                                     ObjectProvider<JdbcOperations> jdbc) {
        this.attachments = attachments;
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
    }

    public void persistRevisionMedia(long kbId, long pageId, long revisionId, String markdown) {
        if (jdbc == null) {
            return;
        }
        Map<String, MarkdownMediaScanner.MediaKind> byUuid = new LinkedHashMap<>();
        for (MarkdownMediaScanner.MediaReference reference
                : MarkdownMediaScanner.scan(markdown)) {
            String uuid = MarkdownMediaScanner.attachmentUuid(reference.src());
            if (uuid == null) {
                continue; // external link: display-only, never fetched
            }
            byUuid.putIfAbsent(uuid, reference.kind());
        }
        jdbc.update("DELETE FROM page_revision_media WHERE revision_id = ?", revisionId);
        for (Map.Entry<String, MarkdownMediaScanner.MediaKind> entry : byUuid.entrySet()) {
            var attachment = attachments.findByUuid(entry.getKey())
                    .filter(candidate -> candidate.getKbId() == kbId)
                    .orElse(null);
            if (attachment == null || attachment.getId() == null) {
                continue; // unknown or cross-kb attachment: not a valid reference
            }
            jdbc.update("""
                            INSERT INTO page_revision_media
                                (revision_id, page_id, kb_id, attachment_id, media_kind, ref_kind)
                            VALUES (?, ?, ?, ?, ?, 'ATTACHMENT')
                            ON DUPLICATE KEY UPDATE media_kind = VALUES(media_kind)
                            """,
                    revisionId, pageId, kbId, attachment.getId(),
                    entry.getValue().name());
        }
    }
}
