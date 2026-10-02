package com.kwiki.indexing.job;

import com.kwiki.indexing.parse.plugin.*;
import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry.ResolvedPipeline;
import com.kwiki.indexing.pipeline.ChunkIndexPort;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.domain.*;
import com.kwiki.wiki.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Set;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IndexingWorkerImportedPdfTest {
    @Test
    void 页面交由插件解析_携带原始页面和修订() throws Exception {
        var plugin = new ParserTestFixtures.FakeParser("third-parser", Set.of(), Set.of(), true);
        var revisions = mock(WikiPageRevisionRepository.class);
        var revision = new WikiPageRevision(7L, 1, "正文", "正文", "导入 报告.pdf", 1L);
        when(revisions.findById(11L)).thenReturn(Optional.of(revision));
        var page = new WikiPage("page", 3L, null, "报告", WikiPage.TYPE_PAGE, 0, 1L);
        var worker = new IndexingWorker(null,null,null,null,null,null,null,null,revisions,null,null,null,8,30,3600);
        Object supplier = ReflectionTestUtils.invokeMethod(worker, "pageDocument", page, 11L);
        var method = supplier.getClass().getDeclaredMethod("load", ResolvedPipeline.class);
        method.setAccessible(true);
        assertThat(method.invoke(supplier, pipeline(plugin))).isSameAs(plugin.document);
        assertThat(plugin.page).isEqualTo(new PageParseRequest(page, revision));
    }
    @Test
    void 第三方附件插件共享同一任务的惰性字节缓存() throws Exception {
        var first = new ParserTestFixtures.FakeParser("first-parser", Set.of("text/plain"), Set.of(), true);
        var second = new ParserTestFixtures.FakeParser("second-parser", Set.of("text/plain"), Set.of(), true);
        var storage = mock(AttachmentStorage.class);
        byte[] bytes = {1,2};
        when(storage.readContent(12L)).thenReturn(bytes);
        var attachment = attachment("text/plain");
        var worker = new IndexingWorker(null,null,null,null,null,null,null,null,null,null,storage,null,8,30,3600);
        var cache = new IndexingWorker.JobIntermediates();
        Object supplier = ReflectionTestUtils.invokeMethod(worker, "attachmentDocument", attachment, cache);
        verifyNoInteractions(storage);
        var method = supplier.getClass().getDeclaredMethod("load", ResolvedPipeline.class);
        method.setAccessible(true);
        assertThat(method.invoke(supplier, pipeline(first))).isSameAs(first.document);
        assertThat(method.invoke(supplier, pipeline(second))).isSameAs(second.document);
        assertThat(first.attachment.attachment()).isSameAs(attachment);
        assertThat(first.attachment.content().get()).isSameAs(bytes);
        verify(storage, times(1)).readContent(12L);
    }
    @Test
    void PDF在Tika下删除分块_导入来源从不创建附件分块() {
        var attachments = mock(AttachmentRepository.class);
        var index = mock(ChunkIndexPort.class);
        var source = attachment("application/pdf");
        when(attachments.findById(9L)).thenReturn(Optional.of(source));
        var worker = new IndexingWorker(null,null,null,null,index,null,null,null,null,attachments,null,null,8,30,3600);
        ReflectionTestUtils.invokeMethod(worker, "executeAttachmentUpsert", "chunks-v1", 1, 9L,
                pipeline(new TikaParserV1(com.kwiki.indexing.parse.DocumentParseService.forTests())), new IndexingWorker.JobIntermediates());
        verify(index).deleteResourceChunks("chunks-v1", "ATTACHMENT", 9L);
        source.markWikiImportSource();
        ReflectionTestUtils.invokeMethod(worker, "executeAttachmentUpsert", "chunks-v2", 2, 9L, null, null);
        verify(index).deleteResourceChunks("chunks-v2", "ATTACHMENT", 9L);
    }
    @Test
    void PDF在V2插件下及第三方声明类型都调用插件并写入分块() {
        for (String id : java.util.List.of(PdfboxMultimodalParserV2.ID, "third-parser")) {
            String type = id.equals(PdfboxMultimodalParserV2.ID) ? "application/pdf" : "text/plain";
            var plugin = new ParserTestFixtures.FakeParser(id, Set.of(type), Set.of(), true);
            var attachments = mock(AttachmentRepository.class);
            var storage = mock(AttachmentStorage.class);
            var index = mock(ChunkIndexPort.class);
            var embeddings = mock(com.kwiki.indexing.pipeline.ChunkEmbeddingPort.class);
            var attachment = attachment(type.toUpperCase(java.util.Locale.ROOT));
            when(attachments.findById(9L)).thenReturn(Optional.of(attachment));
            when(storage.readContent(12L)).thenReturn(new byte[]{1});
            when(embeddings.embed(anyList())).thenReturn(java.util.List.of());
            var worker = new IndexingWorker(null,null,null,null,index,null,null,null,null,attachments,storage,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),8,30,3600);
            var pipeline = new ResolvedPipeline("test", id, "kwiki-chunk-1", plugin,
                    new com.kwiki.indexing.chunk.plugin.KwikiChunkV1(
                            new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(com.kwiki.indexing.multimodal.MultimodalMetrics.class)),
                    embeddings,"model",1024,"kwiki-schema-1",null);
            ReflectionTestUtils.invokeMethod(worker, "executeAttachmentUpsert", "chunks-v2", 2, 9L,
                    pipeline, new IndexingWorker.JobIntermediates());
            assertThat(plugin.attachment.attachment()).isSameAs(attachment);
            verify(index).upsertChunks(any(), eq("chunks-v2"));
            verify(index, never()).deleteResourceChunks(anyString(), anyString(), anyLong());
            verify(storage, times(1)).readContent(12L);
        }
    }

    @Test
    void 插件不读取附件内容时存储保持惰性() throws Exception {
        var plugin = new ParserTestFixtures.FakeParser("third-parser", Set.of("text/plain"), Set.of(), true) {
            @Override
            public com.kwiki.indexing.parse.StructuredDocument parseAttachment(AttachmentParseRequest request) {
                attachment=request;
                return document;
            }
        };
        var storage = mock(AttachmentStorage.class);
        var worker = new IndexingWorker(null,null,null,null,null,null,null,null,null,null,storage,null,8,30,3600);
        Object supplier = ReflectionTestUtils.invokeMethod(worker, "attachmentDocument", attachment("text/plain"), new IndexingWorker.JobIntermediates());
        var method = supplier.getClass().getDeclaredMethod("load", ResolvedPipeline.class);
        method.setAccessible(true);
        assertThat(method.invoke(supplier, pipeline(plugin))).isSameAs(plugin.document);
        verifyNoInteractions(storage);
    }

    private static Attachment attachment(String type) {
        var attachment = new Attachment("source", 3L, 1L, "报告", type, 3);
        ReflectionTestUtils.setField(attachment, "id", 9L);
        attachment.markStored(12L);
        return attachment;
    }
    private static ResolvedPipeline pipeline(DocumentParserPlugin parser) {
        return new ResolvedPipeline("test", parser.id(), "kwiki-chunk-1", parser, null,null,"model",1024,"kwiki-schema-1",null);
    }
}
