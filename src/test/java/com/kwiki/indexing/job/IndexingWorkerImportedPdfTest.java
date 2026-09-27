package com.kwiki.indexing.job;

import com.kwiki.indexing.multimodal.MultimodalIndexingService;
import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry.ResolvedPipeline;
import com.kwiki.indexing.pipeline.ChunkIndexPort;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.domain.SourceDocument;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.domain.WikiPageRevision;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.SourceDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexingWorkerImportedPdfTest {
    @Test
    void initialImportedRevisionUsesPdfImagesUnderPageIdentity() {
        SourceDocumentRepository sources = mock(SourceDocumentRepository.class);
        AttachmentRepository attachments = mock(AttachmentRepository.class);
        AttachmentStorage storage = mock(AttachmentStorage.class);
        MultimodalIndexingService multimodal = mock(MultimodalIndexingService.class);
        DocumentParseService parser = mock(DocumentParseService.class);
        WikiPage page = new WikiPage("page", 3L, null, "报告", WikiPage.TYPE_PAGE, 0, 1L);
        ReflectionTestUtils.setField(page, "id", 7L);
        WikiPageRevision revision = new WikiPageRevision(7L, 1, "正文", "正文", "导入 报告.pdf", 1L);
        Attachment attachment = new Attachment("source", 3L, 1L, "报告.pdf", "application/pdf", 3);
        ReflectionTestUtils.setField(attachment, "id", 9L);
        attachment.markWikiImportSource();
        attachment.markStored(12L);
        byte[] pdf = new byte[]{1, 2, 3};
        StructuredDocument expected = new StructuredDocument(List.of(), "PDF 图片摘要");
        when(sources.findByPageId(7L)).thenReturn(List.of(new SourceDocument(7L, 9L,
                SourceDocument.REL_DERIVED_FROM)));
        when(attachments.findById(9L)).thenReturn(Optional.of(attachment));
        when(storage.readContent(12L)).thenReturn(pdf);
        when(multimodal.buildPdfDocument(parser, 3L, 9L, "报告.pdf", "application/pdf", pdf))
                .thenReturn(expected);
        IndexingWorker worker = worker(sources, attachments, storage);
        ResolvedPipeline pipeline = new ResolvedPipeline("v2", "kwiki-parse-2", "kwiki-chunk-1",
                parser, null, null, null, "model", 1024, 2, null);

        assertThat(worker.importedPdfDocument(page, revision, pipeline, multimodal))
                .isSameAs(expected);
        verify(multimodal).buildPdfDocument(parser, 3L, 9L, "报告.pdf", "application/pdf", pdf);

        WikiPageRevision edited = new WikiPageRevision(7L, 2, "修改后", "修改后", "编辑", 1L);
        assertThat(worker.importedPdfDocument(page, edited, pipeline, multimodal)).isNull();
    }

    @Test
    void importSourceAttachmentNeverGetsItsOwnSearchChunks() {
        AttachmentRepository attachments = mock(AttachmentRepository.class);
        ChunkIndexPort index = mock(ChunkIndexPort.class);
        Attachment source = new Attachment("source", 3L, 1L, "报告.pdf", "application/pdf", 3);
        ReflectionTestUtils.setField(source, "id", 9L);
        source.markWikiImportSource();
        source.markStored(12L);
        when(attachments.findById(9L)).thenReturn(Optional.of(source));
        IndexingWorker worker = new IndexingWorker(null, null, null, null, index, null,
                null, null, null, attachments, null, null, null, null, 8, 30, 3600);

        ReflectionTestUtils.invokeMethod(worker, "executeAttachmentUpsert", "chunks-v2", 2,
                9L, null, null);

        verify(index).deleteResourceChunks("chunks-v2", "ATTACHMENT", 9L);
    }

    private static IndexingWorker worker(SourceDocumentRepository sources,
                                         AttachmentRepository attachments,
                                         AttachmentStorage storage) {
        return new IndexingWorker(null, null, null, null, null, null, null, null,
                null, attachments, storage, null,
                com.kwiki.testutil.StandardTestProperties.providerOf(sources),
                null, 8, 30, 3600);
    }
}
