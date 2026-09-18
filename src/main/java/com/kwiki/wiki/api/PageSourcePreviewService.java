package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.DocumentTypeSniffer;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.SourceDocumentRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * 页面作用域的来源文件预览：摘要、短期地址与兼容字节读取都建立在
 * “当前用户可读该页面 + 附件确实派生自该页面”之上。
 * 摘要只包含格式/文件名/大小，不携带任何持久公开地址；
 * 短期地址在每次请求时重新校验授权、关联、附件状态和大小上限；
 * 兼容字节读取还会重新校验实际内容签名（不信任扩展名或声明 MIME）。
 */
@Service
public class PageSourcePreviewService {

    public record SourceDocumentSummary(String format, String fileName, long byteSize,
                                        String attachmentUuid) {
    }

    public record SourcePreview(byte[] bytes, String fileName, String contentType) {
    }

    public record SourcePreviewRange(byte[] bytes, String fileName, String contentType,
                                     long start, long end, long total) {
    }

    /** 页面授权后签发的短期来源地址；浏览器直取，避免应用服务二次中转大文件。 */
    public record SourcePreviewLink(String url) {
    }

    private final SourceDocumentRepository sources;
    private final AttachmentRepository attachments;
    private final WikiPageRepository pages;
    private final KnowledgeBaseAuthorizationService authorization;
    private final ResourceAuthorizationService resources;
    private final AttachmentStorage storage;
    private final long maxPreviewBytes;
    private final Duration previewTtl;

    public PageSourcePreviewService(SourceDocumentRepository sources,
                                    AttachmentRepository attachments,
                                    WikiPageRepository pages,
                                    KnowledgeBaseAuthorizationService authorization,
                                    AttachmentStorage storage,
                                    @Value("${kwiki.source-preview.max-bytes:20971520}") long maxPreviewBytes) {
        this(sources, attachments, pages, authorization, storage, maxPreviewBytes,
                Duration.ofMinutes(5), null);
    }

    public PageSourcePreviewService(SourceDocumentRepository sources,
                                    AttachmentRepository attachments,
                                    WikiPageRepository pages,
                                    KnowledgeBaseAuthorizationService authorization,
                                    AttachmentStorage storage,
                                    @Value("${kwiki.source-preview.max-bytes:20971520}") long maxPreviewBytes,
                                    ResourceAuthorizationService resources) {
        this(sources, attachments, pages, authorization, storage, maxPreviewBytes,
                Duration.ofMinutes(5), resources);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PageSourcePreviewService(SourceDocumentRepository sources,
                                    AttachmentRepository attachments,
                                    WikiPageRepository pages,
                                    KnowledgeBaseAuthorizationService authorization,
                                    AttachmentStorage storage,
                                    @Value("${kwiki.source-preview.max-bytes:20971520}") long maxPreviewBytes,
                                    @Value("${kwiki.attachments.presign-ttl:300s}") Duration previewTtl,
                                    ResourceAuthorizationService resources) {
        this.sources = sources;
        this.attachments = attachments;
        this.pages = pages;
        this.authorization = authorization;
        this.storage = storage;
        this.maxPreviewBytes = maxPreviewBytes;
        this.previewTtl = previewTtl;
        this.resources = resources;
    }

    /** 页面读取响应中的可选来源摘要；无可预览来源时返回 null（字段可省略）。 */
    public SourceDocumentSummary sourceSummary(CurrentUser user, long kbId, long pageId) {
        requirePage(user, kbId, pageId);
        return previewableSource(kbId, pageId)
                .map(attachment -> new SourceDocumentSummary(
                        DocumentTypeSniffer.previewFormat(attachment.getContentType()),
                        attachment.getFileName(), attachment.getByteSize(), attachment.getUuid()))
                .orElse(null);
    }

    /** 逐次请求的授权内容读取：关联、状态、大小与字节签名全部重新校验。 */
    public SourcePreview readPreview(CurrentUser user, long kbId, long pageId) {
        requirePage(user, kbId, pageId);
        Attachment attachment = previewableSource(kbId, pageId)
                .orElseThrow(() -> new NotFoundException("page source not found"));
        if (attachment.getByteSize() <= 0 || attachment.getByteSize() > maxPreviewBytes) {
            throw new IllegalArgumentException("source document too large to preview");
        }
        byte[] bytes = storage.readContent(attachment.getContentCenterFileId());
        if (bytes.length == 0 || bytes.length > maxPreviewBytes) {
            throw new IllegalArgumentException("source document too large to preview");
        }
        String verified = DocumentTypeSniffer.sniffDocumentType(bytes)
                .orElseThrow(() -> new IllegalArgumentException("source document type not supported"));
        String declared = attachment.getContentType() == null
                ? "" : attachment.getContentType().trim().toLowerCase(Locale.ROOT);
        if (!verified.equals(declared)) {
            throw new IllegalArgumentException("source document type not supported");
        }
        // 内容取回后的最终授权复核：字节返回前权限必须仍然成立。
        requirePage(user, kbId, pageId);
        return new SourcePreview(bytes, attachment.getFileName(), verified);
    }

    /**
     * Page-scoped byte range for the same-origin PDF.js fallback. Authorization and
     * source association are checked on every request; the object store does the
     * actual bounded read instead of the application buffering the whole document.
     */
    public SourcePreviewRange readPreviewRange(CurrentUser user, long kbId, long pageId,
                                               long start, long end) {
        requirePage(user, kbId, pageId);
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid source preview range");
        }
        Attachment attachment = previewableSource(kbId, pageId)
                .orElseThrow(() -> new NotFoundException("page source not found"));
        if (attachment.getByteSize() <= 0 || attachment.getByteSize() > maxPreviewBytes
                || start >= attachment.getByteSize()) {
            throw new IllegalArgumentException("source document too large to preview");
        }
        long effectiveEnd = Math.min(end, attachment.getByteSize() - 1);
        if (effectiveEnd - start + 1 > maxPreviewBytes) {
            throw new IllegalArgumentException("invalid source preview range");
        }
        AttachmentStorage.ContentRange range = storage.readContentRange(
                attachment.getContentCenterFileId(), start, effectiveEnd);
        if (range.total() != attachment.getByteSize()
                || range.start() != start || range.end() < range.start()
                || range.bytes().length != range.end() - range.start() + 1) {
            throw new IllegalArgumentException("source preview range is inconsistent");
        }
        requirePage(user, kbId, pageId);
        String contentType = attachment.getContentType().trim().toLowerCase(Locale.ROOT);
        return new SourcePreviewRange(range.bytes(), attachment.getFileName(), contentType,
                range.start(), range.end(), range.total());
    }

    /**
     * 与字节预览使用同一页面级授权和来源关联校验，但只签发短时地址。
     * 内容下载由浏览器直连内容中心，避免应用服务器因远端大文件读取而占住请求线程。
     */
    public SourcePreviewLink previewLink(CurrentUser user, long kbId, long pageId) {
        requirePage(user, kbId, pageId);
        Attachment attachment = previewableSource(kbId, pageId)
                .orElseThrow(() -> new NotFoundException("page source not found"));
        if (attachment.getByteSize() <= 0 || attachment.getByteSize() > maxPreviewBytes) {
            throw new IllegalArgumentException("source document too large to preview");
        }
        // 预览链接不设置下载文件名：内容中心据此保留 application/pdf 的 inline 行为，
        // 避免 response-content-disposition=attachment 触发浏览器下载。
        String url = storage.downloadLink(attachment.getContentCenterFileId(), null, previewTtl);
        requirePage(user, kbId, pageId);
        return new SourcePreviewLink(url);
    }

    /** 该页面派生、同库、STORED、file id 可用且声明类型可预览的来源附件。 */
    private Optional<Attachment> previewableSource(long kbId, long pageId) {
        WikiPage page = pages.findByIdAndStatus(pageId, WikiPage.STATUS_ACTIVE)
                .orElseThrow(() -> new NotFoundException("page not found"));
        if (page.getKbId() != kbId) {
            throw new NotFoundException("page not found");
        }
        return sources.findByPageId(pageId).stream()
                .map(source -> attachments.findById(source.getAttachmentId()))
                .flatMap(Optional::stream)
                .filter(attachment -> attachment.getKbId() == kbId)
                .filter(attachment -> Attachment.STATUS_STORED.equals(attachment.getStatus()))
                .filter(attachment -> attachment.getContentCenterFileId() != null
                        && attachment.getContentCenterFileId() > 0)
                .filter(attachment -> DocumentTypeSniffer
                        .isPreviewableDeclaredType(attachment.getContentType()))
                .findFirst();
    }

    private void requirePage(CurrentUser user, long kbId, long pageId) {
        if (resources != null) resources.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.READ);
        else authorization.require(user, kbId, WikiAction.READ_PAGE);
    }
}
