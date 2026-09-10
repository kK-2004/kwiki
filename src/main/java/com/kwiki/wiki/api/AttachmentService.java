package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;

import com.kwiki.indexing.job.IndexingJobEnqueuer;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.attach.AttachmentFileNames;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.AttachmentUpload;
import com.kwiki.wiki.attach.MediaContentSniffer;
import com.kwiki.wiki.attach.StoredAttachment;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.persistence.AttachmentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Attachment upload/download with validation before any storage call: file-name
 * traversal is rejected, content types are checked against an allowlist, sizes are
 * bounded, and downloads happen only through short-lived content-center links for
 * members of the knowledge base. Uploads persist a PENDING row without a file id,
 * then transition to STORED only after the storage port returns a validated
 * content-center file id; indexing is enqueued strictly after that success.
 */
@Service
public class AttachmentService {

    private static final Set<String> DEFAULT_ALLOWED_TYPES = Set.of(
            "text/markdown", "text/plain", "text/html",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
            "application/pdf", "application/zip", "application/x-zip-compressed",
            // display/preview media (only images enter the document index)
            "image/png", "image/jpeg", "image/gif", "image/webp",
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/ogg",
            "video/mp4", "video/webm");

    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final KnowledgeBaseAuthorizationService authorization;
    private final IndexingJobEnqueuer indexingJobs;
    private final long maxBytes;
    private final Duration presignTtl;
    private final Set<String> allowedContentTypes;
    private final ResourceAuthorizationService resources;
    private final JdbcOperations jdbc;

    public AttachmentService(AttachmentRepository attachments,
                             AttachmentStorage storage,
                             KnowledgeBaseAuthorizationService authorization,
                             IndexingJobEnqueuer indexingJobs,
                             @Value("${kwiki.attachments.max-bytes:52428800}") long maxBytes,
                             @Value("${kwiki.attachments.presign-ttl:300s}") Duration presignTtl,
                             @Value("${kwiki.attachments.allowed-content-types:}") List<String> allowedContentTypes) {
        this(attachments, storage, authorization, indexingJobs, maxBytes, presignTtl, allowedContentTypes, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AttachmentService(AttachmentRepository attachments,
                             AttachmentStorage storage,
                             KnowledgeBaseAuthorizationService authorization,
                             IndexingJobEnqueuer indexingJobs,
                             @Value("${kwiki.attachments.max-bytes:52428800}") long maxBytes,
                             @Value("${kwiki.attachments.presign-ttl:300s}") Duration presignTtl,
                             @Value("${kwiki.attachments.allowed-content-types:}") List<String> allowedContentTypes,
                             ResourceAuthorizationService resources,
                             ObjectProvider<JdbcOperations> jdbc) {
        this.attachments = attachments;
        this.storage = storage;
        this.authorization = authorization;
        this.indexingJobs = indexingJobs;
        this.maxBytes = maxBytes;
        this.presignTtl = presignTtl;
        this.allowedContentTypes = allowedContentTypes == null || allowedContentTypes.isEmpty()
                ? DEFAULT_ALLOWED_TYPES
                : Set.copyOf(allowedContentTypes.stream()
                        .map(type -> type.toLowerCase(Locale.ROOT))
                        .toList());
        this.resources = resources;
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
    }

    /**
     * Not transactional on purpose: the PENDING row commits before the remote upload
     * so a failed upload leaves a reconciliation trail (PENDING, null file id), and
     * the STORED transition commits only after validated success.
     *
     * <p>Media uploads validate the actual content against the declared MIME
     * (magic bytes) before anything is stored, and cross-check the
     * content-center-reported type afterwards. Only validated image attachments
     * enter the document index; every other attachment stays display-only.</p>
     */
    public Attachment upload(CurrentUser user, long kbId, String rawFileName, String contentType,
                             long byteSize, InputStream content) {
        authorization.require(user, kbId, WikiAction.UPLOAD_ATTACHMENT);

        String safeName = AttachmentFileNames.sanitizeFileName(rawFileName);
        if (byteSize <= 0 || byteSize > maxBytes) {
            throw new IllegalArgumentException("attachment size out of allowed range");
        }
        String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!allowedContentTypes.contains(normalizedType)) {
            throw new IllegalArgumentException("attachment content type is not allowed");
        }
        byte[] bytes = readAll(content);
        if (com.kwiki.indexing.parse.AttachmentIndexEligibility.isMedia(normalizedType)
                && !MediaContentSniffer.matchesDeclaredType(bytes, normalizedType)) {
            throw new IllegalArgumentException("文件内容与声明的媒体类型不符");
        }

        Attachment attachment = attachments.save(new Attachment(
                UUID.randomUUID().toString(), kbId, user.id(), safeName,
                normalizedType, byteSize));

        StoredAttachment stored;
        try {
            stored = storage.store(new AttachmentUpload(
                    safeName, normalizedType, new java.io.ByteArrayInputStream(bytes), byteSize));
        } catch (Exception e) {
            // PENDING row with no file id remains committed for reconciliation;
            // the signed URL and token stay inside the adapter.
            throw new IllegalStateException("attachment storage failed", e);
        }
        if (stored.verifiedByteSize() != attachment.getByteSize()) {
            throw new IllegalStateException("attachment storage returned inconsistent size");
        }
        if (stored.verifiedContentType() != null
                && !stored.verifiedContentType().isBlank()
                && !stored.verifiedContentType().equalsIgnoreCase(normalizedType)
                && !("image/jpg".equalsIgnoreCase(stored.verifiedContentType())
                        && "image/jpeg".equals(normalizedType))) {
            throw new IllegalStateException("attachment storage returned inconsistent content type");
        }
        attachment.markStored(stored.contentCenterFileId());
        attachments.save(attachment);
        // Image-only index eligibility: everything else is display-only.
        if (com.kwiki.indexing.parse.AttachmentIndexEligibility.isIndexableImage(normalizedType)) {
            indexingJobs.enqueueAttachmentUpsert(attachment.getId());
        }
        return attachment;
    }

    private static byte[] readAll(InputStream content) {
        try (content) {
            return content.readAllBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("attachment content could not be read");
        }
    }

    /**
     * Short-lived content-center URL only for STORED attachments that carry a
     * file id, in the caller's knowledge base.
     */
    public String downloadUrl(CurrentUser user, long kbId, String attachmentUuid) {
        Attachment attachment = requireReadableAttachment(user, kbId, attachmentUuid);
        return storage.downloadLink(attachment.getContentCenterFileId(),
                attachment.getFileName(), presignTtl);
    }

    /** Inline bytes of a stored attachment; the durable counterpart of the
     *  short-lived presigned links, used by exports and external viewers. */
    public record AttachmentContent(byte[] bytes, String fileName, String contentType) {}

    public AttachmentContent readContent(CurrentUser user, long kbId, String attachmentUuid) {
        Attachment attachment = requireReadableAttachment(user, kbId, attachmentUuid);
        return new AttachmentContent(storage.readContent(attachment.getContentCenterFileId()),
                attachment.getFileName(), attachment.getContentType());
    }

    /** KB read + STORED + usable file id; import sources additionally need a
     *  readable derived page. */
    private Attachment requireReadableAttachment(CurrentUser user, long kbId, String attachmentUuid) {
        authorization.require(user, kbId, WikiAction.READ_PAGE);
        Attachment attachment = attachments.findByUuid(attachmentUuid)
                .filter(candidate -> candidate.getKbId() == kbId)
                .filter(candidate -> Attachment.STATUS_STORED.equals(candidate.getStatus()))
                .orElseThrow(() -> new NotFoundException("attachment not found"));
        if (Attachment.PURPOSE_WIKI_IMPORT_SOURCE.equals(attachment.getPurpose()) && resources != null) {
            Integer visible = jdbcVisibleImportPages(attachment.getId(), user);
            if (visible == 0) throw new NotFoundException("attachment not found");
        }
        Long fileId = attachment.getContentCenterFileId();
        if (fileId == null || fileId <= 0) {
            // Fail closed: a STORED row without a file id is unusable.
            throw new NotFoundException("attachment not found");
        }
        return attachment;
    }

    /**
     * Authorized preview link for inline media (image/audio/video). Beyond the
     * knowledge-base read check, a media attachment embedded in page content is
     * granted only while at least one ACTIVE page revision readable to the
     * caller still references it — archived pages stop being an authorization
     * source. Links are always re-issued fresh from the stable attachment
     * identity; signed URLs are never persisted.
     */
    public String mediaPreviewUrl(CurrentUser user, long kbId, String attachmentUuid) {
        String url = downloadUrl(user, kbId, attachmentUuid);
        Attachment attachment = attachments.findByUuid(attachmentUuid)
                .filter(candidate -> candidate.getKbId() == kbId)
                .orElseThrow(() -> new NotFoundException("attachment not found"));
        boolean media = com.kwiki.indexing.parse.AttachmentIndexEligibility
                .isMedia(attachment.getContentType());
        if (media && jdbc != null && resources != null) {
            Integer referenced = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM page_revision_media m JOIN wiki_page p ON p.id = m.page_id "
                            + "WHERE m.attachment_id = ? AND p.status = 'ACTIVE'",
                    Integer.class, attachment.getId());
            if (referenced != null && referenced > 0) {
                Integer readable = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM page_revision_media m JOIN wiki_page p ON p.id = m.page_id "
                                + "WHERE m.attachment_id = ? AND p.status = 'ACTIVE' AND p.kb_id = ?",
                        Integer.class, attachment.getId(), kbId);
                boolean anyReadable = false;
                if (readable != null && readable > 0) {
                    List<Long> pageIds = jdbc.query(
                            "SELECT DISTINCT m.page_id FROM page_revision_media m "
                                    + "JOIN wiki_page p ON p.id = m.page_id "
                                    + "WHERE m.attachment_id = ? AND p.status = 'ACTIVE'",
                            (rs, row) -> rs.getLong(1), attachment.getId());
                    anyReadable = pageIds.stream()
                            .anyMatch(pageId -> resources.can(user, pageId, ResourceAction.READ));
                }
                if (!anyReadable) {
                    throw new NotFoundException("attachment not found");
                }
            }
            // No page reference yet (fresh upload, not saved into a page): the
            // knowledge-base read permission granted above remains sufficient.
        }
        return url;
    }

    private int jdbcVisibleImportPages(Long attachmentId, CurrentUser user) {
        // The provenance query is deliberately narrow: an import source is readable
        // only when at least one derived page is readable to the current user.
        if (jdbc == null) return 0;
        List<Long> pageIds = jdbc.query("SELECT p.id FROM source_document s JOIN wiki_page p ON p.id = s.page_id WHERE s.attachment_id = ? AND p.status = 'ACTIVE'", (rs, row) -> rs.getLong(1), attachmentId);
        return pageIds.stream().anyMatch(pageId -> resources.can(user, pageId, ResourceAction.READ)) ? 1 : 0;
    }

    /**
     * Archives metadata and de-indexes the content. Physical deletion is
     * intentionally not attempted: content-center SDK 0.1.3 exposes no delete and
     * retention is owned by the content center.
     */
    @Transactional
    public void archive(CurrentUser user, long kbId, String attachmentUuid) {
        authorization.require(user, kbId, WikiAction.UPLOAD_ATTACHMENT);
        Attachment attachment = attachments.findByUuid(attachmentUuid)
                .filter(candidate -> candidate.getKbId() == kbId)
                .orElseThrow(() -> new NotFoundException("attachment not found"));
        attachment.archive();
        attachments.save(attachment);
        indexingJobs.enqueueAttachmentDelete(attachment.getId());
    }
}
