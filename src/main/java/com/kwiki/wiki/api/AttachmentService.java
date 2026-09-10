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
 * 附件上传/下载，在任何存储调用之前先做校验：拒绝文件名
 * 路径穿越、按白名单校验内容类型、限制大小，
 * 且下载只通过短时效的内容中心链接提供给
 * 知识库成员。上传先持久化一条不带 file id 的 PENDING 记录，
 * 只有当存储端口返回经过校验的
 * 内容中心 file id 之后才转为 STORED；索引构建严格在该成功之后入队。
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
            // 展示/预览媒体（只有图片会进入文档索引）
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
     * 故意不加事务：PENDING 记录在远程上传之前就提交，
     * 因此上传失败会留下可供对账的痕迹（PENDING、file id 为 null），
     * 而 STORED 的转换只在校验成功之后才提交。
     *
     * <p>媒体上传会在存储任何内容之前，将真实内容与声明的 MIME
     * （魔数字节）做校验，随后再交叉核对
     * 内容中心返回的类型。只有通过校验的图片附件
     * 才会进入文档索引；其他所有附件都仅供展示。</p>
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
            // 不带 file id 的 PENDING 记录仍会提交，以便对账；
            // 签名 URL 与 token 始终留在适配器内部。
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
        // 仅图片具备索引资格：其他一切仅供展示。
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
     * 仅对带有 file id 且处于 STORED 状态的附件签发短时效的
     * 内容中心 URL，且必须属于调用方所在的知识库。
     */
    public String downloadUrl(CurrentUser user, long kbId, String attachmentUuid) {
        Attachment attachment = requireReadableAttachment(user, kbId, attachmentUuid);
        return storage.downloadLink(attachment.getContentCenterFileId(),
                attachment.getFileName(), presignTtl);
    }

    /** 已存储附件的内联字节；它是短时效预签名链接的
     * 持久对应物，供导出与外部查看器使用。 */
    public record AttachmentContent(byte[] bytes, String fileName, String contentType) {}

    public AttachmentContent readContent(CurrentUser user, long kbId, String attachmentUuid) {
        Attachment attachment = requireReadableAttachment(user, kbId, attachmentUuid);
        return new AttachmentContent(storage.readContent(attachment.getContentCenterFileId()),
                attachment.getFileName(), attachment.getContentType());
    }

    /** 需要知识库读权限 + STORED + 可用的 file id；导入源还额外需要
     * 一个可读的派生页面。 */
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
            // 故障关闭：处于 STORED 但没有 file id 的记录不可用。
            throw new NotFoundException("attachment not found");
        }
        return attachment;
    }

    /**
     * 内联媒体（图片/音频/视频）的授权预览链接。除了
     * 知识库读权限校验之外，嵌入页面内容中的媒体附件
     * 只有在至少存在一个调用方可读的 ACTIVE 页面
     * 修订版本仍在引用它时才会被授权 —— 已归档页面不再是
     * 授权依据。链接始终依据稳定的附件标识
     * 重新签发；签名 URL 绝不会被持久化。
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
            // 尚无页面引用（刚上传，尚未保存进页面）：
            // 前述的知识库读权限即已足够。
        }
        return url;
    }

    private int jdbcVisibleImportPages(Long attachmentId, CurrentUser user) {
        // 溯源查询被刻意收窄：导入源只有在当前用户
        // 至少有一个可读的派生页面时才可读。
        if (jdbc == null) return 0;
        List<Long> pageIds = jdbc.query("SELECT p.id FROM source_document s JOIN wiki_page p ON p.id = s.page_id WHERE s.attachment_id = ? AND p.status = 'ACTIVE'", (rs, row) -> rs.getLong(1), attachmentId);
        return pageIds.stream().anyMatch(pageId -> resources.can(user, pageId, ResourceAction.READ)) ? 1 : 0;
    }

    /**
     * 归档元数据并将内容从索引中移除。物理删除是
     * 刻意不尝试的：内容中心 SDK 0.1.3 未提供删除接口，
     * 且保留策略由内容中心负责。
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
