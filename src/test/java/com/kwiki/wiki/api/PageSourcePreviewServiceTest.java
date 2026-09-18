package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.MembershipLookup;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.domain.SourceDocument;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.SourceDocumentRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 页面来源预览的授权边界：摘要与字节都要求
 * “可读页面 + 页面确实派生自该附件 + STORED + 签名一致”，
 * 且任何响应都不携带永久公开地址。
 */
@ExtendWith(MockitoExtension.class)
class PageSourcePreviewServiceTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "root", true);
    private static final CurrentUser OUTSIDER = new CurrentUser(5L, "stranger", false);
    private static final long KB = 1L;
    private static final long PAGE_ID = 3L;

    @Mock
    SourceDocumentRepository sources;
    @Mock
    AttachmentRepository attachments;
    @Mock
    WikiPageRepository pages;
    @Mock
    AttachmentStorage storage;

    private PageSourcePreviewService service;

    @BeforeEach
    void setUp() {
        KnowledgeBaseAuthorizationService auth = new KnowledgeBaseAuthorizationService(
                new ObjectProvider<>() {
                    @Override
                    public MembershipLookup getIfAvailable() { return null; }
                });
        lenient().when(pages.findByIdAndStatus(PAGE_ID, WikiPage.STATUS_ACTIVE))
                .thenReturn(Optional.of(activePage(KB)));
        service = new PageSourcePreviewService(sources, attachments, pages, auth, storage, 20 * 1024 * 1024L);
    }

    private WikiPage activePage(long kbId) {
        WikiPage page = new WikiPage("eeeeeeee-0000-0000-0000-00000000000b", kbId, null,
                "sourced page", WikiPage.TYPE_PAGE, 0, ADMIN.id());
        org.springframework.test.util.ReflectionTestUtils.setField(page, "id", PAGE_ID);
        return page;
    }

    private Attachment attachment(long id, long kbId, String uuid, String fileName,
                                  String contentType, long byteSize) {
        Attachment attachment = new Attachment(uuid, kbId, ADMIN.id(), fileName, contentType, byteSize);
        org.springframework.test.util.ReflectionTestUtils.setField(attachment, "id", id);
        attachment.markStored(900L + id);
        return attachment;
    }

    private void linkSource(Attachment attachment) {
        when(sources.findByPageId(PAGE_ID)).thenReturn(List.of(
                new SourceDocument(PAGE_ID, attachmentId(attachment), SourceDocument.REL_DERIVED_FROM)));
        when(attachments.findById(attachmentId(attachment))).thenReturn(Optional.of(attachment));
    }

    private static long attachmentId(Attachment attachment) {
        return (Long) org.springframework.test.util.ReflectionTestUtils.getField(attachment, "id");
    }

    private static byte[] pdfBytes() { return "%PDF-1.7\n%kwiki mock\n".getBytes(StandardCharsets.US_ASCII); }

    private static byte[] docxBytes() {
        byte[] bytes = new byte[512];
        bytes[0] = 'P'; bytes[1] = 'K'; bytes[2] = 3; bytes[3] = 4;
        byte[] marker = "[Content_Types].xml".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, bytes, 30, marker.length);
        return bytes;
    }

    @Test
    void validPdfSourceProducesSummaryAndProtectedBytes() {
        Attachment pdf = attachment(11, KB, "att-pdf", "manual.pdf",
                "application/pdf", pdfBytes().length);
        linkSource(pdf);
        when(storage.readContent(911L)).thenReturn(pdfBytes());

        PageSourcePreviewService.SourceDocumentSummary summary =
                service.sourceSummary(ADMIN, KB, PAGE_ID);
        assertThat(summary.format()).isEqualTo("PDF");
        assertThat(summary.fileName()).isEqualTo("manual.pdf");
        assertThat(summary.byteSize()).isEqualTo(pdfBytes().length);
        assertThat(summary.attachmentUuid()).isEqualTo("att-pdf");

        PageSourcePreviewService.SourcePreview preview = service.readPreview(ADMIN, KB, PAGE_ID);
        assertThat(preview.contentType()).isEqualTo("application/pdf");
        assertThat(new String(preview.bytes(), StandardCharsets.US_ASCII)).startsWith("%PDF-");
    }

    @Test
    void previewLinkUsesShortLivedBrowserDownloadWithoutReadingContent() {
        Attachment pdf = attachment(21, KB, "att-link", "manual.pdf",
                "application/pdf", pdfBytes().length);
        linkSource(pdf);
        when(storage.downloadLink(921L, null, Duration.ofMinutes(5)))
                .thenReturn("https://files.example.test/signed.pdf");

        PageSourcePreviewService.SourcePreviewLink link =
                service.previewLink(ADMIN, KB, PAGE_ID);

        assertThat(link.url()).isEqualTo("https://files.example.test/signed.pdf");
        verify(storage, never()).readContent(921L);
    }

    @Test
    void validDocxSourcePreviewsAfterSignatureCheck() {
        Attachment docx = attachment(12, KB, "att-docx", "spec.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxBytes().length);
        linkSource(docx);
        when(storage.readContent(912L)).thenReturn(docxBytes());

        assertThat(service.sourceSummary(ADMIN, KB, PAGE_ID).format()).isEqualTo("DOCX");
        PageSourcePreviewService.SourcePreview preview = service.readPreview(ADMIN, KB, PAGE_ID);
        assertThat(preview.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(preview.bytes()[0]).isEqualTo((byte) 'P');
    }

    @Test
    void markdownSourceAndSourcelessPagesYieldNoSummaryAndNoPreview() {
        Attachment markdown = attachment(13, KB, "att-md", "notes.md", "text/markdown", 64);
        linkSource(markdown);
        assertThat(service.sourceSummary(ADMIN, KB, PAGE_ID)).isNull();
        assertThatThrownBy(() -> service.readPreview(ADMIN, KB, PAGE_ID))
                .isInstanceOf(NotFoundException.class);

        when(sources.findByPageId(PAGE_ID)).thenReturn(List.of());
        assertThat(service.sourceSummary(ADMIN, KB, PAGE_ID)).isNull();
        assertThatThrownBy(() -> service.readPreview(ADMIN, KB, PAGE_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void archivedPageAndForeignPageAreIndistinguishableFromMissing() {
        when(pages.findByIdAndStatus(PAGE_ID, WikiPage.STATUS_ACTIVE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.readPreview(ADMIN, KB, PAGE_ID))
                .isInstanceOf(NotFoundException.class);

        when(pages.findByIdAndStatus(PAGE_ID, WikiPage.STATUS_ACTIVE))
                .thenReturn(Optional.of(activePage(999L)));
        assertThatThrownBy(() -> service.readPreview(ADMIN, KB, PAGE_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void mismatchedOrUnusableAttachmentsAreRejected() {
        Attachment foreign = attachment(14, 999L, "att-foreign", "secret.pdf",
                "application/pdf", pdfBytes().length);
        linkSource(foreign);
        assertThat(service.sourceSummary(ADMIN, KB, PAGE_ID)).isNull();
        assertThatThrownBy(() -> service.readPreview(ADMIN, KB, PAGE_ID))
                .isInstanceOf(NotFoundException.class);

        Attachment archived = attachment(15, KB, "att-archived", "old.pdf",
                "application/pdf", pdfBytes().length);
        archived.archive();
        when(sources.findByPageId(PAGE_ID)).thenReturn(List.of(
                new SourceDocument(PAGE_ID, 15L, SourceDocument.REL_DERIVED_FROM)));
        when(attachments.findById(15L)).thenReturn(Optional.of(archived));
        assertThatThrownBy(() -> service.readPreview(ADMIN, KB, PAGE_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void declaredTypeDisagreeingWithSignatureIsRejected() {
        Attachment lying = attachment(16, KB, "att-lying", "fake.pdf",
                "application/pdf", docxBytes().length);
        linkSource(lying);
        when(storage.readContent(916L)).thenReturn(docxBytes());
        assertThatThrownBy(() -> service.readPreview(ADMIN, KB, PAGE_ID))
                .isInstanceOf(IllegalArgumentException.class);

        Attachment junk = attachment(17, KB, "att-junk", "broken.pdf",
                "application/pdf", 16);
        when(sources.findByPageId(PAGE_ID)).thenReturn(List.of(
                new SourceDocument(PAGE_ID, 17L, SourceDocument.REL_DERIVED_FROM)));
        when(attachments.findById(17L)).thenReturn(Optional.of(junk));
        when(storage.readContent(917L)).thenReturn("not a document".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> service.readPreview(ADMIN, KB, PAGE_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedSourceIsRejectedBeforeFetchingBytes() {
        Attachment huge = attachment(18, KB, "att-huge", "big.pdf",
                "application/pdf", 20 * 1024 * 1024L + 1);
        linkSource(huge);
        assertThatThrownBy(() -> service.readPreview(ADMIN, KB, PAGE_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actualContentAboveLimitIsRejectedEvenWhenMetadataIsSmall() {
        Attachment pdf = attachment(22, KB, "att-size", "manual.pdf", "application/pdf", 10);
        linkSource(pdf);
        when(storage.readContent(922L)).thenReturn(new byte[20 * 1024 * 1024 + 1]);
        assertThatThrownBy(() -> service.readPreview(ADMIN, KB, PAGE_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contentEndpointReturnsPdfBytesInlineWithoutCacheOrRedirect() {
        PageSourcePreviewService previews = mock(PageSourcePreviewService.class);
        when(previews.readPreview(ADMIN, KB, PAGE_ID)).thenReturn(
                new PageSourcePreviewService.SourcePreview(pdfBytes(), "manual.pdf", "application/pdf"));
        PageController controller = new PageController(null, null, null, null, null, null, null, previews);
        var response = controller.sourcePreviewContent(ADMIN, KB, PAGE_ID);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(pdfBytes());
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).isEqualTo("inline");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, no-store");
        assertThat(response.getHeaders().getLocation()).isNull();
    }

    @Test
    void revokedPageAccessIsRefusedWithoutLeakingSourceMetadata() {
        KnowledgeBaseAuthorizationService auth = new KnowledgeBaseAuthorizationService(
                new ObjectProvider<>() {
                    @Override
                    public MembershipLookup getIfAvailable() { return null; }
                });
        JdbcOperations jdbc = mock(JdbcOperations.class);
        lenient().when(jdbc.queryForObject(
                eq("SELECT kb_id FROM wiki_page WHERE id = ? AND status = 'ACTIVE'"),
                eq(Long.class), eq(PAGE_ID))).thenReturn(KB);
        lenient().when(jdbc.queryForMap(
                eq("SELECT kb_id, owner_id, audience_mode FROM wiki_page WHERE id = ? AND status = 'ACTIVE'"),
                eq(PAGE_ID))).thenReturn(Map.of("kb_id", KB, "owner_id", ADMIN.id(),
                "audience_mode", "KB_MEMBERS"));
        lenient().when(jdbc.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(null);
        ResourceAuthorizationService resources = new ResourceAuthorizationService(
                new ObjectProvider<>() {
                    @Override
                    public JdbcOperations getIfAvailable() { return jdbc; }
                }, auth);
        PageSourcePreviewService guarded = new PageSourcePreviewService(
                sources, attachments, pages, auth, storage, 20 * 1024 * 1024L, resources);

        assertThatThrownBy(() -> guarded.sourceSummary(OUTSIDER, KB, PAGE_ID))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guarded.readPreview(OUTSIDER, KB, PAGE_ID))
                .isInstanceOf(AccessDeniedException.class);
    }
}
