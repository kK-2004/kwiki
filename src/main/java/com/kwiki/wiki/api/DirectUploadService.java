package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.MediaContentSniffer;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.persistence.AttachmentRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/*文件字节由浏览器直接送往存储。会话把服务商定位符绑定到归属人和知识库。 */
@Service
public class DirectUploadService {
    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final AttachmentService attachmentService;
    private final KnowledgeBaseAuthorizationService authorization;
    private final JdbcOperations jdbc;

    public DirectUploadService(AttachmentRepository attachments, AttachmentStorage storage,
                               AttachmentService attachmentService, KnowledgeBaseAuthorizationService authorization,
                               ObjectProvider<JdbcOperations> jdbc) {
        this.attachments = attachments;
        this.storage = storage;
        this.attachmentService = attachmentService;
        this.authorization = authorization;
        this.jdbc = jdbc.getIfAvailable();
    }

    public record Request(String fileName, String contentType, long byteSize, String purpose,
                          String hashVersion, String prefixSha256, String fullSha256) {
        /*面向旧的未去重调用方的兼容构造函数。 */
        public Request(String fileName, String contentType, long byteSize, String purpose) {
            this(fileName, contentType, byteSize, purpose, null, null, null);
        }
    }
    public record Ticket(String state, String attachmentUuid, Map<String, String> headers, long expiresIn,
                         String putUrl) {
        public Ticket(String attachmentUuid, String putUrl, Map<String, String> headers, long expiresIn) {
            this("UPLOAD", attachmentUuid, headers, expiresIn, putUrl);
        }
    }
    public record PrefixLookup(int candidates) {}
    private record Session(String storageKey, String source, Instant expiresAt, Long fileId, Long blobId) {}
    private record Blob(long id, String status, Long fileId) {}

    public PrefixLookup lookupPrefix(CurrentUser user, long kbId, String hashVersion,
                                     String prefixSha256, long byteSize) {
        authorization.require(user, kbId, WikiAction.UPLOAD_ATTACHMENT);
        requireDb();
        validateHash(hashVersion, prefixSha256, null, byteSize, false);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM attachment_blob WHERE hash_version = ? AND prefix_sha256 = UNHEX(?) AND byte_size = ? AND status IN ('PENDING','STORED')",
                Integer.class, hashVersion, prefixSha256, byteSize);
        return new PrefixLookup(count == null ? 0 : count);
    }

    @Transactional
    public Ticket initiate(CurrentUser user, long kbId, Request request) {
        authorization.require(user, kbId, WikiAction.UPLOAD_ATTACHMENT);
        requireDb();
        String purpose = request.purpose() == null ? Attachment.PURPOSE_GENERAL : request.purpose();
        if (!Attachment.PURPOSE_GENERAL.equals(purpose) && !Attachment.PURPOSE_WIKI_IMPORT_SOURCE.equals(purpose)) {
            throw new IllegalArgumentException("invalid upload purpose");
        }
        String type = request.contentType() == null ? "" : request.contentType().trim().toLowerCase(Locale.ROOT);
        if (Attachment.PURPOSE_WIKI_IMPORT_SOURCE.equals(purpose)) {
            type = importContentType(request.fileName(), type, request.byteSize());
        }
        String name = attachmentService.validateDirectMetadata(request.fileName(), type, request.byteSize());
        boolean fingerprinted = request.hashVersion() != null || request.prefixSha256() != null || request.fullSha256() != null;
        if (fingerprinted) validateHash(request.hashVersion(), request.prefixSha256(), request.fullSha256(), request.byteSize(), true);

        if (fingerprinted) {
            Blob exact = findBlob(request.hashVersion(), request.fullSha256(), request.byteSize());
            if (exact != null && "STORED".equals(exact.status()) && exact.fileId() != null) {
                Attachment reference = new Attachment(UUID.randomUUID().toString(), kbId, user.id(), name, type, request.byteSize());
                if (Attachment.PURPOSE_WIKI_IMPORT_SOURCE.equals(purpose)) reference.markWikiImportSource();
                reference.markStoredFromBlob(exact.id(), exact.fileId());
                reference = attachments.saveAndFlush(reference);
                attachmentService.enqueueDirectAttachment(reference);
                return new Ticket("REUSED", reference.getUuid(), Map.of(), 0, "");
            }
            if (exact != null && "PENDING".equals(exact.status())) {
                return new Ticket("PENDING", "", Map.of(), 0, "");
            }
        }

        Long blobId = null;
        if (fingerprinted) {
            try {
                jdbc.update("INSERT INTO attachment_blob (hash_version, prefix_sha256, full_sha256, byte_size, content_type) VALUES (?, UNHEX(?), UNHEX(?), ?, ?)",
                        request.hashVersion(), request.prefixSha256(), request.fullSha256(), request.byteSize(), type);
                blobId = jdbc.queryForObject("SELECT id FROM attachment_blob WHERE hash_version = ? AND full_sha256 = UNHEX(?) AND byte_size = ?",
                        Long.class, request.hashVersion(), request.fullSha256(), request.byteSize());
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                Blob exact = findBlob(request.hashVersion(), request.fullSha256(), request.byteSize());
                if (exact != null && "STORED".equals(exact.status()) && exact.fileId() != null) {
                    Attachment reference = new Attachment(UUID.randomUUID().toString(), kbId, user.id(), name, type, request.byteSize());
                    if (Attachment.PURPOSE_WIKI_IMPORT_SOURCE.equals(purpose)) reference.markWikiImportSource();
                    reference.markStoredFromBlob(exact.id(), exact.fileId());
                    reference = attachments.saveAndFlush(reference);
                    attachmentService.enqueueDirectAttachment(reference);
                    return new Ticket("REUSED", reference.getUuid(), Map.of(), 0, "");
                }
                return new Ticket("PENDING", "", Map.of(), 0, "");
            }
        }
        var attachment = new Attachment(UUID.randomUUID().toString(), kbId, user.id(), name, type, request.byteSize());
        if (Attachment.PURPOSE_WIKI_IMPORT_SOURCE.equals(purpose)) attachment.markWikiImportSource();
        attachment = attachments.saveAndFlush(attachment);
        if (blobId != null) {
            jdbc.update("UPDATE attachment SET blob_id = ? WHERE id = ?", blobId, attachment.getId());
        }
        var upload = storage.initiateUpload(name, type, request.byteSize());
        // 允许在 URL 过期前发起的 PUT 正常完成，并重试丢失的完成响应。
        Instant expiresAt = Instant.now().plusSeconds(Math.min(upload.expiresIn(), 86400) + 3600);
        jdbc.update("INSERT INTO attachment_upload_session (attachment_id, storage_key, source, expires_at, provider_file_id) VALUES (?, ?, ?, ?, ?)",
                attachment.getId(), upload.storageKey(), upload.source(), Timestamp.from(expiresAt), upload.fileId());
        return new Ticket("UPLOAD", attachment.getUuid(), Map.of("Content-Type", type), upload.expiresIn(), upload.putUrl());
    }

    @Transactional
    public Attachment complete(CurrentUser user, long kbId, String uuid) {
        authorization.require(user, kbId, WikiAction.UPLOAD_ATTACHMENT);
        requireDb();
        // 在多个实例之间串行化重复请求，确保索引和 STORED 状态流转只发生一次。
        Attachment attachment = attachments.findByUuidForUpdate(uuid)
                .filter(a -> a.getKbId() == kbId && a.getUploadedBy() == user.id())
                .orElseThrow(() -> new NotFoundException("upload session not found"));
        if (attachment.isStored()) return attachment;
        if (!Attachment.STATUS_PENDING.equals(attachment.getStatus())) throw new NotFoundException("upload session not found");
        var sessions = attachment.getBlobId() == null
                ? jdbc.query("SELECT storage_key, source, expires_at, provider_file_id FROM attachment_upload_session WHERE attachment_id = ?",
                (rs, n) -> new Session(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getObject(4, Long.class), null), attachment.getId())
                : jdbc.query("SELECT s.storage_key, s.source, s.expires_at, s.provider_file_id, a.blob_id FROM attachment_upload_session s JOIN attachment a ON a.id = s.attachment_id WHERE s.attachment_id = ?",
                (rs, n) -> new Session(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getObject(4, Long.class), rs.getObject(5, Long.class)), attachment.getId());
        if (sessions.isEmpty() || !sessions.getFirst().expiresAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("上传会话已过期，请重新选择文件上传");
        }
        Session session = sessions.getFirst();
        var stored = storage.completeUpload(session.storageKey(), session.source(), attachment.getContentType(), attachment.getByteSize(), session.fileId());
        if (stored.verifiedByteSize() != attachment.getByteSize()
                || !attachment.getContentType().equalsIgnoreCase(stored.verifiedContentType())) {
            throw new IllegalStateException("attachment storage returned inconsistent metadata");
        }
        if (com.kwiki.indexing.parse.AttachmentIndexEligibility.isMedia(attachment.getContentType())
                && !MediaContentSniffer.matchesDeclaredType(storage.readPrefix(stored.contentCenterFileId(), 12), attachment.getContentType())) {
            throw new IllegalArgumentException("文件内容与声明的媒体类型不符");
        }
        if (session.blobId() != null) {
            jdbc.update("UPDATE attachment_blob SET content_center_file_id = ?, status = 'STORED' WHERE id = ? AND status = 'PENDING'",
                    stored.contentCenterFileId(), session.blobId());
            Blob blob = jdbc.query("SELECT id, status, content_center_file_id FROM attachment_blob WHERE id = ?",
                    (rs, n) -> new Blob(rs.getLong(1), rs.getString(2), rs.getObject(3, Long.class)), session.blobId())
                    .stream().findFirst().orElseThrow(() -> new IllegalStateException("attachment blob disappeared"));
            if (blob.fileId() == null || !"STORED".equals(blob.status())) throw new IllegalStateException("attachment blob is not stored");
            attachment.markStoredFromBlob(blob.id(), blob.fileId());
        } else {
            attachment.markStored(stored.contentCenterFileId());
        }
        attachments.saveAndFlush(attachment);
        attachmentService.enqueueDirectAttachment(attachment);
        jdbc.update("DELETE FROM attachment_upload_session WHERE attachment_id = ?", attachment.getId());
        return attachment;
    }

    private static String importContentType(String name, String type, long size) {
        if (size <= 0 || size > 20 * 1024 * 1024L) throw new WikiImportValidationException("请选择非空文件，大小不超过 20 MB");
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String expected;
        if (lower.endsWith(".pdf")) expected = "application/pdf";
        else if (lower.endsWith(".docx")) expected = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        else if (lower.endsWith(".md") || lower.endsWith(".markdown")) expected = "text/markdown";
        else throw new WikiImportValidationException("仅支持 Markdown、DOCX 和 PDF 文件");
        if (!type.isBlank() && !type.equals("application/octet-stream") && !type.equals(expected)
                && !(expected.equals("text/markdown") && type.equals("text/plain"))) {
            throw new WikiImportValidationException("文件类型与扩展名不一致");
        }
        return expected;
    }

    private void requireDb() {
        if (jdbc == null) throw new IllegalStateException("database is unavailable");
    }

    private Blob findBlob(String hashVersion, String fullSha256, long byteSize) {
        return jdbc.query("SELECT id, status, content_center_file_id FROM attachment_blob WHERE hash_version = ? AND full_sha256 = UNHEX(?) AND byte_size = ?",
                (rs, n) -> new Blob(rs.getLong(1), rs.getString(2), rs.getObject(3, Long.class)), hashVersion, fullSha256, byteSize)
                .stream().findFirst().orElse(null);
    }

    private static void validateHash(String version, String prefix, String full, long byteSize, boolean requireFull) {
        if (version == null || !version.matches("sha256-prefix-1m-v1")) throw new IllegalArgumentException("unsupported fingerprint version");
        if (prefix == null || !prefix.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("invalid prefix fingerprint");
        if (full != null && !full.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("invalid full fingerprint");
        if (requireFull && full == null) throw new IllegalArgumentException("full fingerprint is required before upload");
        if (byteSize <= 0) throw new IllegalArgumentException("invalid byte size");
    }
}
