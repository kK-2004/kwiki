package com.kwiki.indexing.parse.plugin;

import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.indexing.multimodal.MultimodalIndexingService;
import com.kwiki.indexing.multimodal.PdfMultimodalParser;
import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.plugin.IndexCapability;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.AttachmentStorageException;
import com.kwiki.wiki.domain.*;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.SourceDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.kwiki.testutil.StandardTestProperties.providerOf;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PdfboxMultimodalParserV2Test {
    private final DocumentParseService parser = mock(DocumentParseService.class);
    private final MultimodalSwitchReadiness readiness = mock(MultimodalSwitchReadiness.class);
    private final MultimodalIndexingService multimodal = mock(MultimodalIndexingService.class);
    private final SourceDocumentRepository sources = mock(SourceDocumentRepository.class);
    private final AttachmentRepository attachments = mock(AttachmentRepository.class);
    private final AttachmentStorage storage = mock(AttachmentStorage.class);

    private PdfboxMultimodalParserV2 plugin(MultimodalIndexingService service,
                                           SourceDocumentRepository repository) {
        return new PdfboxMultimodalParserV2(parser, readiness, providerOf(service),
                providerOf(repository), attachments, storage);
    }

    @Test
    void availabilityDescribesMissingConfigurationAndService() {
        when(readiness.ready()).thenReturn(true);
        PdfboxMultimodalParserV2 plugin = plugin(multimodal, sources);
        assertThat(plugin.availability().available()).isTrue();
        assertThat(plugin.id()).isEqualTo("kwiki-parse-2");
        assertThat(plugin.label()).isEqualTo("pdfbox-v2");
        assertThat(plugin.requiredCapabilities()).containsExactly(IndexCapability.RESOURCE_IDS);
        assertThat(plugin.indexableAttachmentTypes()).containsExactly("application/pdf");
        when(readiness.ready()).thenReturn(false);
        when(readiness.missingConfiguration()).thenReturn(List.of("KWIKI_VISION_API_KEY"));
        assertThat(plugin.availability().unavailableReason()).isEqualTo("缺少配置：KWIKI_VISION_API_KEY");
        assertThat(plugin(null, sources).availability().unavailableReason()).isEqualTo("多模态服务未启用");
    }

    @Test
    void importPdfOnlyAssemblesTextAndNonPdfPreservesTikaOutput() {
        byte[] content = {1};
        when(parser.parsePdfMultimodal("source.pdf", "APPLICATION/PDF", content)).thenReturn(
                new PdfMultimodalParser.PdfExtraction(List.of(new PdfMultimodalParser.TextItem("图片前"),
                        new PdfMultimodalParser.ImageRefItem(0, 0),
                        new PdfMultimodalParser.TextItem("图片后")), List.of(), List.of()));
        PdfboxMultimodalParserV2 plugin = plugin(multimodal, sources);
        assertThat(plugin.parseImport(new ImportParseRequest(3, "source.pdf", "APPLICATION/PDF", content))
                .plainText()).isEqualTo("图片前\n\n图片后");
        verify(readiness).requireReadyFor(PdfboxMultimodalParserV2.ID);
        StructuredDocument expected = new StructuredDocument(List.of(), "正文");
        when(parser.parse(eq("source.docx"), eq("document/type"), any(InputStream.class)))
                .thenAnswer(call -> {
                    assertThat(((InputStream) call.getArgument(2)).readAllBytes()).isEqualTo(content);
                    return expected;
                });
        assertThat(plugin(null, sources).parseImport(
                new ImportParseRequest(3, "source.docx", "document/type", content))).isSameAs(expected);
    }

    @Test
    void missingServiceAndReadinessFailureClosePdfParsingBeforeReadingBytes() {
        PdfboxMultimodalParserV2 unavailable = plugin(null, sources);
        assertThatThrownBy(() -> unavailable.parsePage(new PageParseRequest(page(), revision(1, "导入 报告.pdf"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("multimodal parser version requested but multimodal indexing is disabled");
        assertThatThrownBy(() -> unavailable.parseAttachment(new AttachmentParseRequest(attachment(3L),
                () -> { throw new AssertionError("不应读取附件"); }))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> unavailable.parseImport(
                new ImportParseRequest(3, "source.pdf", "application/pdf", new byte[]{1})))
                .isInstanceOf(IllegalStateException.class);
        doThrow(new IllegalStateException("missing configuration")).when(readiness)
                .requireReadyFor(PdfboxMultimodalParserV2.ID);
        assertThatThrownBy(() -> plugin(multimodal, sources).parseImport(
                new ImportParseRequest(3, "source.pdf", "application/pdf", new byte[]{1})))
                .isInstanceOf(IllegalStateException.class).hasMessage("missing configuration");
        verifyNoInteractions(parser, storage);
    }

    @Test
    void attachmentReadsSupplierOnlyDuringParsingAndPreservesPdfIdentity() {
        AtomicInteger reads = new AtomicInteger();
        byte[] pdf = {1, 2, 3};
        Attachment attachment = attachment(3L);
        StructuredDocument expected = new StructuredDocument(List.of(), "PDF 图片摘要");
        when(multimodal.buildPdfDocument(parser, 3L, 9L, "报告.pdf", "application/pdf", pdf)).thenReturn(expected);
        AttachmentParseRequest request = new AttachmentParseRequest(attachment,
                () -> { reads.incrementAndGet(); return pdf; });
        PdfboxMultimodalParserV2 plugin = plugin(multimodal, sources);
        plugin.availability();
        assertThat(reads).hasValue(0);
        assertThat(plugin.parseAttachment(request)).isSameAs(expected);
        assertThat(reads).hasValue(1);
    }

    @Test
    void importedFirstRevisionUsesStoredPdfAndEditedRevisionUsesMarkdown() {
        Attachment attachment = attachment(3L);
        byte[] pdf = {1, 2, 3};
        when(sources.findByPageId(7L)).thenReturn(List.of(new SourceDocument(7L, 9L, SourceDocument.REL_DERIVED_FROM)));
        when(attachments.findById(9L)).thenReturn(Optional.of(attachment));
        when(storage.readContent(12L)).thenReturn(pdf);
        StructuredDocument expected = new StructuredDocument(List.of(), "PDF 图片摘要");
        when(multimodal.buildPdfDocument(parser, 3L, 9L, "报告.pdf", "application/pdf", pdf)).thenReturn(expected);
        PdfboxMultimodalParserV2 plugin = plugin(multimodal, sources);
        assertThat(plugin.parsePage(new PageParseRequest(page(), revision(1, "导入 报告.pdf")))).isSameAs(expected);
        verify(multimodal).buildPdfDocument(parser, 3L, 9L, "报告.pdf", "application/pdf", pdf);
        StructuredDocument edited = new StructuredDocument(List.of(), "修改后");
        when(multimodal.buildPageDocument(3L, 7L, 11L, "正文")).thenReturn(edited);
        assertThat(plugin.parsePage(new PageParseRequest(page(), revision(2, "编辑")))).isSameAs(edited);
    }

    @Test
    void invalidImportSourcesFallBackAndMissingContentIdIsPermanent() {
        PdfboxMultimodalParserV2 plugin = plugin(multimodal, sources);
        StructuredDocument expected = new StructuredDocument(List.of(), "正文");
        when(multimodal.buildPageDocument(3L, 7L, 11L, "正文")).thenReturn(expected);
        when(sources.findByPageId(7L)).thenReturn(List.of(new SourceDocument(7L, 9L, "OTHER"),
                new SourceDocument(7L, 10L, SourceDocument.REL_DERIVED_FROM)));
        assertThat(plugin.parsePage(new PageParseRequest(page(), revision(1, "导入 报告.pdf")))).isSameAs(expected);
        for (Attachment invalid : List.of(attachment(4L), new Attachment("a", 3L, 1L,
                "a.pdf", "application/pdf", 3), storedNonPdf())) {
            when(attachments.findById(10L)).thenReturn(Optional.of(invalid));
            assertThat(plugin.parsePage(new PageParseRequest(page(), revision(1, "导入 报告.pdf")))).isSameAs(expected);
        }
        assertThat(plugin(multimodal, null).parsePage(new PageParseRequest(page(), revision(1, "导入 报告.pdf"))))
                .isSameAs(expected);
        assertThat(plugin.parsePage(new PageParseRequest(page(), revision(1, null)))).isSameAs(expected);
        assertThat(plugin.parsePage(new PageParseRequest(page(), revision(1, "编辑")))).isSameAs(expected);
        Attachment broken = attachment(3L);
        ReflectionTestUtils.setField(broken, "contentCenterFileId", null);
        when(attachments.findById(10L)).thenReturn(Optional.of(broken));
        assertThatThrownBy(() -> plugin.parsePage(new PageParseRequest(page(), revision(1, "导入 报告.pdf"))))
                .isInstanceOfSatisfying(AttachmentStorageException.class,
                        error -> assertThat(error.getCategory()).isEqualTo(AttachmentStorageException.Category.PERMANENT))
                .hasMessage("import source has no content-center file id for indexing");
        verifyNoInteractions(storage);
    }

    private WikiPage page() {
        WikiPage page = new WikiPage("page", 3L, null, "报告", WikiPage.TYPE_PAGE, 0, 1L);
        ReflectionTestUtils.setField(page, "id", 7L);
        return page;
    }

    private WikiPageRevision revision(int number, String note) {
        WikiPageRevision revision = new WikiPageRevision(7L, number, "正文", "正文", note, 1L);
        ReflectionTestUtils.setField(revision, "id", 11L);
        return revision;
    }

    private Attachment attachment(Long kbId) {
        Attachment attachment = new Attachment("source", kbId, 1L, "报告.pdf", "application/pdf", 3);
        ReflectionTestUtils.setField(attachment, "id", 9L);
        attachment.markWikiImportSource();
        attachment.markStored(12L);
        return attachment;
    }

    private Attachment storedNonPdf() {
        Attachment attachment = new Attachment("source", 3L, 1L, "报告.txt", "text/plain", 3);
        attachment.markStored(12L);
        return attachment;
    }
}
