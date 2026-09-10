package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.AttachmentStorageException;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import com.kwiki.wiki.persistence.WikiPageRevisionRepository;
import com.kwiki.wiki.render.MarkdownPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageExportServiceTest {

    private static final long KB = 7L;
    private static final long PAGE_ID = 5L;
    private static final CurrentUser USER = new CurrentUser(9L, "editor", false);
    private static final String MEDIA_UUID = "1a078d69-d751-4b4e-8b2e-bdc9ecc7a189";
    private static final String BASE = "https://kwiki.example.com";
    private static final String CDN_URL = "https://cdn.example.com/kwiki/1a078d69.png";

    @Mock WikiPageRepository pages;
    @Mock WikiPageRevisionRepository revisions;
    @Mock AttachmentRepository attachments;
    @Mock AttachmentStorage storage;
    @Mock MarkdownPort markdown;
    @Mock KnowledgeBaseAuthorizationService authorization;
    @Mock ResourceAuthorizationService resources;

    private PageExportService service() {
        return new PageExportService(pages, revisions, attachments, storage,
                markdown, authorization, resources);
    }

    private void stubPage() {
        WikiPage page = mock(WikiPage.class);
        lenient().when(page.getKbId()).thenReturn(KB);
        lenient().when(page.getTitle()).thenReturn("含图片文档");
        when(pages.findByIdAndStatus(PAGE_ID, WikiPage.STATUS_ACTIVE)).thenReturn(Optional.of(page));
    }

    @Test
    void rewritesAttachmentTokensToCdnLinksAsASingleFile() {
        stubPage();
        Attachment attachment = new Attachment(MEDIA_UUID, KB, USER.id(), "截图.png", "image/png", 3);
        attachment.markStored(42L);
        when(attachments.findByUuid(MEDIA_UUID)).thenReturn(Optional.of(attachment));
        when(storage.cdnLink(42L)).thenReturn(CDN_URL);

        String snapshot = "中文正文 ![截图说明](attachment://" + MEDIA_UUID + ") 之后\n\n"
                + "<img src=\"attachment://" + MEDIA_UUID + "\" alt=\"同一张图\">\n\n"
                + "![外链](https://example.com/cat.png)";
        PageExportService.ExportPayload payload =
                service().export(USER, KB, PAGE_ID, "md", snapshot, null, BASE);

        // Single markdown file — never a ZIP, media bytes are not bundled.
        assertThat(payload.fileName()).isEqualTo("含图片文档.md");
        assertThat(payload.contentType()).isEqualTo("text/markdown;charset=UTF-8");
        String doc = new String(payload.content(), StandardCharsets.UTF_8);
        // The whole ![alt](src) / <img> construct survives; the token becomes a CDN link.
        assertThat(doc).contains("![截图说明](" + CDN_URL + ")");
        assertThat(doc).contains("<img src=\"" + CDN_URL + "\" alt=\"同一张图\">");
        // Manually typed external links stay exactly as authored.
        assertThat(doc).contains("![外链](https://example.com/cat.png)");
        assertThat(doc).doesNotContain("attachment://");
    }

    @Test
    void fallsBackToTheAppContentEndpointWhenNoCdnLinkCanBeIssued() {
        stubPage();
        // Unknown attachment: no stored file to address.
        when(attachments.findByUuid(MEDIA_UUID)).thenReturn(Optional.empty());
        String unknown = "![x](attachment://" + MEDIA_UUID + ")";
        PageExportService.ExportPayload payload =
                service().export(USER, KB, PAGE_ID, "md", unknown, null, BASE);
        String fallback = BASE + "/api/v1/knowledge-bases/" + KB
                + "/attachments/" + MEDIA_UUID + "/content";
        assertThat(new String(payload.content(), StandardCharsets.UTF_8))
                .contains("![x](" + fallback + ")");

        // CDN issuance failure on a stored file degrades the same way.
        Attachment attachment = new Attachment(MEDIA_UUID, KB, USER.id(), "截图.png", "image/png", 3);
        attachment.markStored(42L);
        when(attachments.findByUuid(MEDIA_UUID)).thenReturn(Optional.of(attachment));
        when(storage.cdnLink(42L)).thenThrow(new AttachmentStorageException(
                AttachmentStorageException.Category.TRANSIENT, "cdn link failed"));
        payload = service().export(USER, KB, PAGE_ID, "md", unknown, null, BASE);
        assertThat(new String(payload.content(), StandardCharsets.UTF_8))
                .contains("![x](" + fallback + ")");
    }

    @Test
    void htmlExportRendersTheRewrittenMarkdownThroughThePort() {
        stubPage();
        when(markdown.renderToHtml("![图](/api/v1/knowledge-bases/" + KB
                + "/attachments/" + MEDIA_UUID + "/content)"))
                .thenReturn("<img src=\"/api/v1/knowledge-bases/" + KB
                        + "/attachments/" + MEDIA_UUID + "/content\">");
        PageExportService.ExportPayload payload = service().export(USER, KB, PAGE_ID, "html",
                "![图](attachment://" + MEDIA_UUID + ")", null, "");

        assertThat(payload.fileName()).isEqualTo("含图片文档.html");
        assertThat(payload.contentType()).isEqualTo("text/html;charset=UTF-8");
        String html = new String(payload.content(), StandardCharsets.UTF_8);
        // Blank base falls back to same-origin relative links.
        assertThat(html).contains("<img src=\"/api/v1/knowledge-bases/" + KB
                + "/attachments/" + MEDIA_UUID + "/content\">");
        assertThat(html).startsWith("<!DOCTYPE html>");
        // Standalone files have no script to interpret kwiki's media attributes;
        // the wrapper CSS translates data-align / data-width-percent instead.
        assertThat(html).contains("[data-align=\"center\"]");
        assertThat(html).contains("[data-width-percent=\"25\"]");
    }

    @Test
    void exportsExternalOnlySnapshotsByteForByte() {
        stubPage();
        String snapshot = "仅外链 ![猫](https://example.com/cat.png) 与表格\n\n"
                + "| 项目 | 说明 |\n| --- | --- |\n| 导入 | 保留 |";
        PageExportService.ExportPayload payload =
                service().export(USER, KB, PAGE_ID, "md", snapshot, null, BASE);

        assertThat(payload.fileName()).isEqualTo("含图片文档.md");
        assertThat(new String(payload.content(), StandardCharsets.UTF_8)).isEqualTo(snapshot);
    }
}
