package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.AttachmentStorageException;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import com.kwiki.wiki.persistence.WikiPageRevisionRepository;
import com.kwiki.wiki.render.MarkdownMediaScanner;
import com.kwiki.wiki.render.MarkdownPort;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 精确快照的 Markdown/HTML 导出。编辑模式导出编辑器
 * 提交的草稿快照（包括未保存的文本）；阅读模式导出
 * 当前正在查看的修订版本 —— 两条路径都不会发布任何内容。每一个
 * {@code attachment://uuid} 引用都会被改写为已存储文件的
 * 内容中心 CDN 链接，且保持原结构不变（按 CDN 策略为持久链接，
 * 这点不同于预签名预览链接）；当无法签发 CDN 链接时，
 * 该引用会回退到 kwiki 自有的已授权内容端点。手动输入的
 * 外部链接完全保持作者所写的样子，且绝不会被抓取。结果始终是
 * 单个 {@code .md}/{@code .html} 文件 —— 不打 ZIP 包。
 */
@Service
public class PageExportService {

    public record ExportPayload(byte[] content, String fileName, String contentType) {}

    private final WikiPageRepository pages;
    private final WikiPageRevisionRepository revisions;
    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final MarkdownPort markdown;
    private final KnowledgeBaseAuthorizationService authorization;
    private final ResourceAuthorizationService resources;

    public PageExportService(WikiPageRepository pages,
                             WikiPageRevisionRepository revisions,
                             AttachmentRepository attachments,
                             AttachmentStorage storage,
                             MarkdownPort markdown,
                             KnowledgeBaseAuthorizationService authorization,
                             ResourceAuthorizationService resources) {
        this.pages = pages;
        this.revisions = revisions;
        this.attachments = attachments;
        this.storage = storage;
        this.markdown = markdown;
        this.authorization = authorization;
        this.resources = resources;
    }

    /**
     * @param snapshotMarkdown 编辑器快照（可能未保存）；当
     * {@code revisionNo} 为 null 时使用，否则导出已存储的
     * 修订版本（需要读权限）
     * @param baseUrl 用于构建持久媒体链接的绝对源；
     * 为空表示使用同源的相对链接
     */
    public ExportPayload export(CurrentUser user, long kbId, long pageId, String format,
                                String snapshotMarkdown, Integer revisionNo, String baseUrl) {
        WikiPage page = pages.findByIdAndStatus(pageId, WikiPage.STATUS_ACTIVE)
                .filter(candidate -> candidate.getKbId() == kbId)
                .orElseThrow(() -> new NotFoundException("page not found"));
        String snapshot;
        if (revisionNo == null) {
            requireEdit(user, kbId, pageId);
            snapshot = snapshotMarkdown == null ? "" : snapshotMarkdown;
        } else {
            requireRead(user, kbId, pageId);
            snapshot = revisions.findByPageIdAndRevisionNo(pageId, revisionNo)
                    .orElseThrow(() -> new NotFoundException("revision not found"))
                    .getMarkdown();
        }
        boolean html = "html".equalsIgnoreCase(format);
        String baseName = sanitizeFileName(page.getTitle());
        String markdownOut = rewriteAttachmentTokens(snapshot, kbId, baseUrl);
        String content = html
                ? htmlDocument(markdown.renderToHtml(markdownOut))
                : markdownOut;
        return new ExportPayload(content.getBytes(StandardCharsets.UTF_8),
                baseName + (html ? ".html" : ".md"),
                html ? "text/html;charset=UTF-8" : "text/markdown;charset=UTF-8");
    }

    /** 把每一个 attachment://uuid 标记替换为持久的媒体 URL，
     * 同时保持其外围的 ![alt](…) / <img> / <a> 结构不变。 */
    private String rewriteAttachmentTokens(String snapshot, long kbId, String baseUrl) {
        var references = MarkdownMediaScanner.scan(snapshot);
        if (references.isEmpty()) {
            return snapshot;
        }
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        Map<String, String> linkByUuid = new HashMap<>();
        StringBuilder rewritten = new StringBuilder();
        int cursor = 0;
        for (MarkdownMediaScanner.MediaReference reference : references) {
            String uuid = MarkdownMediaScanner.attachmentUuid(reference.src());
            if (uuid == null) {
                continue;
            }
            rewritten.append(snapshot, cursor, reference.start())
                    .append(snapshot.substring(reference.start(), reference.end())
                            .replace("attachment://" + uuid, mediaLink(kbId, uuid, base, linkByUuid)));
            cursor = reference.end();
        }
        rewritten.append(snapshot, cursor, snapshot.length());
        return rewritten.toString();
    }

    /** 当已存储文件能解析出 CDN 链接时使用 CDN；否则使用 kwiki 自有的
     * 已授权内容端点，使该引用绝不会无声失效。 */
    private String mediaLink(long kbId, String uuid, String base, Map<String, String> linkByUuid) {
        return linkByUuid.computeIfAbsent(uuid, key -> {
            Long fileId = attachments.findByUuid(key)
                    .filter(candidate -> candidate.getKbId() == kbId)
                    .filter(Attachment::isStored)
                    .map(Attachment::getContentCenterFileId)
                    .filter(id -> id != null && id > 0)
                    .orElse(null);
            if (fileId != null) {
                try {
                    return storage.cdnLink(fileId);
                } catch (AttachmentStorageException e) {
                    // 继续向下落到下面的应用端点
                }
            }
            return base + "/api/v1/knowledge-bases/" + kbId + "/attachments/" + key + "/content";
        });
    }

    private static String sanitizeFileName(String title) {
        String cleaned = title == null ? "export" : title.trim();
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        if (cleaned.isBlank()) cleaned = "export";
        return cleaned.substring(0, Math.min(cleaned.length(), 80));
    }

    private String htmlDocument(String body) {
        return "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>kwiki export</title>\n<style>\n"
                + "body{font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;"
                + "max-width:820px;margin:40px auto;padding:0 20px;color:#252a2a;line-height:1.8}\n"
                + "img,video{max-width:100%;height:auto}\n"
                + "pre{background:#f4f7f4;padding:14px;border-radius:8px;overflow:auto}\n"
                + "table{border-collapse:collapse}th,td{border:1px solid #e1eae3;padding:8px 12px}\n"
                // kwiki 媒体约定（data-align / data-width-percent）能在
                // 净化中保留，但独立文件中没有脚本会解释它们。
                + "[data-align=\"center\"]{display:block;margin-left:auto;margin-right:auto}\n"
                + "[data-align=\"right\"]{display:block;margin-left:auto}\n"
                + "[data-align=\"left\"]{display:block;margin-right:auto}\n"
                + "[data-width-percent=\"25\"]{width:25%}[data-width-percent=\"50\"]{width:50%}"
                + "[data-width-percent=\"75\"]{width:75%}[data-width-percent=\"100\"]{width:100%}\n"
                + "</style>\n</head>\n<body>\n" + body + "\n</body>\n</html>\n";
    }

    private void requireEdit(CurrentUser user, long kbId, long pageId) {
        if (resources != null) {
            resources.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.EDIT);
        } else {
            authorization.require(user, kbId, WikiAction.EDIT_PAGE);
        }
    }

    private void requireRead(CurrentUser user, long kbId, long pageId) {
        if (resources != null) {
            resources.requireInKnowledgeBase(user, kbId, pageId, ResourceAction.READ);
        } else {
            authorization.require(user, kbId, WikiAction.READ_PAGE);
        }
    }
}
