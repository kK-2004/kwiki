package com.kwiki.wiki.api;

import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.render.MarkdownMediaScanner;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在保存修订版本的同一事务中持久化该页面修订版本的
 * 媒体引用（page_revision_media）。只记录稳定的
 * attachment://uuid 引用 —— 外部链接绝不会被
 * 抓取或跟踪。这些记录驱动已授权的预览校验、归档
 * 独占性判定以及物理清理顺序。
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
                continue; // 外部链接：仅供展示，绝不抓取
            }
            byUuid.putIfAbsent(uuid, reference.kind());
        }
        jdbc.update("DELETE FROM page_revision_media WHERE revision_id = ?", revisionId);
        for (Map.Entry<String, MarkdownMediaScanner.MediaKind> entry : byUuid.entrySet()) {
            var attachment = attachments.findByUuid(entry.getKey())
                    .filter(candidate -> candidate.getKbId() == kbId)
                    .orElse(null);
            if (attachment == null || attachment.getId() == null) {
                continue; // 未知或跨知识库的附件：不是有效引用
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
