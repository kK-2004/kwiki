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
 * Markdown/HTML export of an exact snapshot. Editing mode exports the draft
 * snapshot the editor submits (including unsaved text); reading mode exports
 * the revision being viewed — neither path publishes anything. Every
 * {@code attachment://uuid} reference is rewritten, construct intact, to the
 * content-center CDN link of the stored file (durable by CDN policy, unlike
 * the presigned preview links); when no CDN link can be issued the reference
 * falls back to kwiki's own authorized content endpoint. Manually typed
 * external links stay exactly as authored and are never fetched. The result is
 * always a single {@code .md}/{@code .html} file — no ZIP packaging.
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
     * @param snapshotMarkdown editor snapshot (may be unsaved); used when
     *                         {@code revisionNo} is null, otherwise the stored
     *                         revision is exported (read permission required)
     * @param baseUrl          absolute origin used to build durable media links;
     *                         blank means same-origin relative links
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

    /** Replaces every attachment://uuid token with a durable media URL,
     *  keeping the surrounding ![alt](…) / <img> / <a> construct intact. */
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

    /** CDN link when the stored file can resolve one; otherwise kwiki's own
     *  authorized content endpoint so the reference never silently dies. */
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
                    // fall through to the app endpoint below
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
                // kwiki media conventions (data-align / data-width-percent) survive
                // sanitization but no script interprets them in a standalone file.
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
