package com.kwiki.indexing.job;

import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.indexing.pipeline.ChunkIndexPort;
import com.kwiki.indexing.pipeline.IndexedVersion;
import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.domain.WikiPageRevision;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import com.kwiki.wiki.persistence.WikiPageRevisionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多目标写入契约（任务 5.2/5.3/5.4/5.6）：同一资源事件的目标在
 * built 配置等价时共享解析/分块/嵌入结果（embedding 只调用一次）；
 * 配置不同（维度不同）时分别嵌入并写入各自的维度；单个目标失败
 * 只退避该目标，不影响其它目标成功。
 */
@ExtendWith(MockitoExtension.class)
class VersionedMultiWriteTest {

    private static final long KB = 1L;

    @Mock
    IndexingJobTargetClaimer claimer;

    @Mock
    org.springframework.jdbc.core.JdbcOperations jdbc;

    @Mock
    ChunkEmbeddingPort embeddings;

    @Mock
    ChunkIndexPort index;

    @Mock
    SearchIndexVersionRepository versionRepository;

    @Mock
    WikiPageRepository pages;

    @Mock
    com.kwiki.wiki.persistence.KnowledgeBaseRepository knowledgeBases;

    @Mock
    WikiPageRevisionRepository revisions;

    @Mock
    AttachmentRepository attachments;

    @Mock
    AttachmentStorage storage;

    private IndexingWorker worker;

    private static EditableIndexConfig config(int dims) {
        return new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", dims == 6 ? "alt-dims" : "default",
                "text-embedding-v4", dims, "kwiki-schema-1");
    }

    @BeforeEach
    void setUp() {
        var external = com.kwiki.testutil.StubModelRegistry.defaultEmbedding("text-embedding-v4", 4, embeddings)
                .withEmbedding("alt-dims", "text-embedding-v4", 6, embeddings);
        IndexingProperties indexing = new IndexingProperties(
                List.of(
                        new IndexingProperties.Manifest("gen-4", "kwiki-parse-1", "kwiki-chunk-1",
                                "default", "text-embedding-v4", 4, "kwiki-schema-1"),
                        new IndexingProperties.Manifest("gen-6", "kwiki-parse-1", "kwiki-chunk-1",
                                "alt-dims", "text-embedding-v4", 6, "kwiki-schema-1")),
                Map.of(),
                new IndexingProperties.Rebuild(50, 2, 20),
                new IndexingProperties.Catchup(100, Duration.ofSeconds(30)),
                new IndexingProperties.Capacity(20, 8, Duration.ofSeconds(5)),
                new IndexingProperties.Management(false));
        VersionedIndexingPipelineRegistry registry = new VersionedIndexingPipelineRegistry(
                indexing, external,
                com.kwiki.indexing.parse.plugin.ParserTestFixtures.tika(DocumentParseService.forTests()),
                new com.kwiki.indexing.chunk.plugin.ChunkerRegistry(java.util.List.of(new com.kwiki.indexing.chunk.plugin.KwikiChunkV1(
                        new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(com.kwiki.indexing.multimodal.MultimodalMetrics.class)))), com.kwiki.indexing.schema.SchemaTestFixtures.schemas());

        registerVersion(1, "kwiki-chunks-v1", config(4));
        registerVersion(2, "kwiki-chunks-v2", config(6));

        worker = new IndexingWorker(claimer, new IndexingJobTargetStore(provider(jdbc)),
                registry, StandardTestProperties.providerOf(versionRepository),
                index, null, pages, knowledgeBases, revisions, attachments, storage,
                new SimpleMeterRegistry(), 8, 30, 3600);

        stubPage(7L, 103L, "# 标题\n\n" + "多目标内容".repeat(80));
        lenient().when(jdbc.queryForList(anyString(), eq(Long.class), anyLong()))
                .thenReturn(List.of(1L));
        lenient().when(embeddings.embed(any())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            // 重新打桩时 Mockito 会以 null 实参回放旧 answer。
            return texts == null ? List.of()
                    : texts.stream().map(text -> new float[4]).toList();
        });
    }

    private void registerVersion(int number, String physical, EditableIndexConfig config) {
        SearchIndexVersion version = SearchIndexVersion.bootstrapped(number, physical, config,
                "hash");
        lenient().when(versionRepository.findByVersionNumber(number))
                .thenReturn(Optional.of(version));
    }

    private void stubPage(long pageId, long revisionId, String markdown) {
        WikiPage page = new WikiPage("uuid-" + pageId, KB, null, "工程手册",
                WikiPage.TYPE_PAGE, 0, 1L);
        org.springframework.test.util.ReflectionTestUtils.setField(page, "id", pageId);
        lenient().when(pages.findById(pageId)).thenReturn(Optional.of(page));
        WikiPageRevision revision = new WikiPageRevision(pageId, 3, markdown, markdown, "note", 1L);
        org.springframework.test.util.ReflectionTestUtils.setField(revision, "id", revisionId);
        lenient().when(revisions.findById(revisionId)).thenReturn(Optional.of(revision));
    }

    /** 嵌入结果按调用顺序给出不同维度。 */
    private void embedReturns(int... dimsInOrder) {
        java.util.Iterator<Integer> dims = java.util.Arrays.stream(dimsInOrder).iterator();
        lenient().when(embeddings.embed(any())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            if (texts == null) {
                return List.of();
            }
            int dim = dims.next();
            return texts.stream().map(text -> new float[dim]).toList();
        });
    }

    private static Map<String, Object> target(long targetId, int version, String physical) {
        Map<String, Object> row = new HashMap<>();
        row.put("target_id", targetId);
        row.put("job_id", 100L);
        row.put("event_id", null);
        row.put("target_version", version);
        row.put("physical_name", physical);
        row.put("target_state", "LEASED");
        row.put("attempts", 1);
        row.put("job_type", "UPSERT");
        row.put("resource_type", "PAGE");
        row.put("resource_id", 7L);
        row.put("revision_id", 103L);
        row.put("expected_lifecycle_version", null);
        return row;
    }

    @Test
    void equivalentBuiltConfigsShareIntermediatesWithOneEmbeddingCall() {
        registerVersion(3, "kwiki-chunks-v3", config(4));
        embedReturns(4);
        IndexingWorker.JobIntermediates shared = new IndexingWorker.JobIntermediates();

        worker.process(target(11L, 1, "kwiki-chunks-v1"), shared);
        worker.process(target(12L, 3, "kwiki-chunks-v3"), shared);

        verify(embeddings, times(1)).embed(any());
        verify(index).upsertChunks(any(), eq("kwiki-chunks-v1"));
        verify(index).upsertChunks(any(), eq("kwiki-chunks-v3"));
        verify(jdbc).update(contains("indexing_job_target SET state = 'COMPLETED'"), eq(11L));
        verify(jdbc).update(contains("indexing_job_target SET state = 'COMPLETED'"), eq(12L));
    }

    @Test
    void differentDimensionsEmbedSeparatelyAndWriteTheirOwnVectors() {
        embedReturns(4, 6);
        IndexingWorker.JobIntermediates shared = new IndexingWorker.JobIntermediates();

        worker.process(target(21L, 1, "kwiki-chunks-v1"), shared);
        worker.process(target(22L, 2, "kwiki-chunks-v2"), shared);

        verify(embeddings, times(2)).embed(any());
        ArgumentCaptor<IndexedVersion> written = ArgumentCaptor.forClass(IndexedVersion.class);
        verify(index, times(2)).upsertChunks(written.capture(), anyString());
        assertThat(written.getAllValues().get(0).childVectors().get(0)).hasSize(4);
        assertThat(written.getAllValues().get(1).childVectors().get(0)).hasSize(6);
    }

    @Test
    void shadowTargetFailureDoesNotFailTheActiveTarget() {
        embedReturns(4);
        IndexingWorker.JobIntermediates shared = new IndexingWorker.JobIntermediates();
        org.mockito.Mockito.lenient().doThrow(new RuntimeException("shadow index rejected"))
                .when(index).upsertChunks(any(), eq("kwiki-chunks-v2"));

        worker.process(target(31L, 1, "kwiki-chunks-v1"), shared);
        worker.process(target(32L, 2, "kwiki-chunks-v2"), shared);

        // 主目标成功；影子目标按自身退避。
        verify(jdbc).update(contains("indexing_job_target SET state = 'COMPLETED'"), eq(31L));
        verify(jdbc).update(contains("'RETRY_WAIT'"), any(Integer.class), anyString(),
                anyString(), any(Integer.class), anyLong(), anyLong(), eq(32L));
        verify(jdbc, never()).update(contains("indexing_job_target SET state = 'COMPLETED'"),
                eq(32L));
    }

    @Test
    void dimensionMismatchFailsTheTargetBeforeWriting() {
        embedReturns(6); // 期待 4 维流水线却返回 6 维
        IndexingWorker.JobIntermediates shared = new IndexingWorker.JobIntermediates();

        worker.process(target(41L, 1, "kwiki-chunks-v1"), shared);

        verify(index, never()).upsertChunks(any(), anyString());
        verify(jdbc).update(contains("'RETRY_WAIT'"), any(Integer.class), anyString(),
                anyString(), any(Integer.class), anyLong(), anyLong(), eq(41L));
    }

    @Test
    void 不同分块器同数量不同正文分别嵌入页面() {
        assertSeparateVectors(false, false, false);
    }

    @Test
    void 不同分块器不同数量分别嵌入页面() {
        assertSeparateVectors(true, false, false);
    }

    @Test
    void 不同解析器同分块器分别嵌入页面() {
        assertSeparateVectors(false, true, false);
    }

    @Test
    void 不同分块器同数量不同正文分别嵌入附件() {
        assertSeparateVectors(false, false, true);
    }

    private void assertSeparateVectors(boolean differentCount, boolean differentParser, boolean attachment) {
        var firstParser = new com.kwiki.indexing.parse.plugin.ParserTestFixtures.FakeParser(
                "parser-a", java.util.Set.of("text/plain"), java.util.Set.of(), true);
        firstParser.document = new com.kwiki.indexing.parse.StructuredDocument(List.of(), "解析甲");
        var secondParser = differentParser ? new com.kwiki.indexing.parse.plugin.ParserTestFixtures.FakeParser(
                "parser-b", java.util.Set.of("text/plain"), java.util.Set.of(), true) : firstParser;
        if (differentParser) secondParser.document = new com.kwiki.indexing.parse.StructuredDocument(List.of(), "解析乙");
        var firstChunker = new FakeChunker("chunker-a", 1);
        var secondChunker = differentParser ? firstChunker : new FakeChunker("chunker-b", differentCount ? 2 : 1);
        List<List<String>> requests = new java.util.ArrayList<>();
        ChunkEmbeddingPort recording = texts -> {
            requests.add(List.copyOf(texts));
            return texts.stream().map(text -> new float[]{text.hashCode(), 0, 0, 0}).toList();
        };
        var registry = org.mockito.Mockito.mock(VersionedIndexingPipelineRegistry.class);
        for (int number = 4; number <= 5; number++) {
            var parser = number == 4 ? firstParser : secondParser;
            var chunker = number == 4 ? firstChunker : secondChunker;
            var config = new EditableIndexConfig(parser.id(), chunker.id(), "default", "text-embedding-v4", 4, "kwiki-schema-1");
            registerVersion(number, "kwiki-chunks-v" + number, config);
            when(registry.resolve(config)).thenReturn(Optional.of(new VersionedIndexingPipelineRegistry.ResolvedPipeline(
                    "test-" + number, parser.id(), chunker.id(), parser, chunker, recording, "text-embedding-v4", 4, "kwiki-schema-1", null)));
        }
        worker = new IndexingWorker(claimer, new IndexingJobTargetStore(provider(jdbc)), registry,
                StandardTestProperties.providerOf(versionRepository), index, null, pages, knowledgeBases, revisions,
                attachments, storage, new SimpleMeterRegistry(), 8, 30, 3600);
        var shared = new IndexingWorker.JobIntermediates();
        if (attachment) {
            var source = new com.kwiki.wiki.domain.Attachment("attachment", KB, 1L, "正文", "text/plain", 1);
            org.springframework.test.util.ReflectionTestUtils.setField(source, "id", 7L);
            source.markStored(12L);
            when(attachments.findById(7L)).thenReturn(Optional.of(source));
            when(storage.readContent(12L)).thenReturn(new byte[]{1});
        }
        for (int number = 4; number <= 5; number++) {
            var row = target(50L + number, number, "kwiki-chunks-v" + number);
            if (attachment) { row.put("resource_type", "ATTACHMENT"); row.put("revision_id", null); }
            worker.process(row, shared);
        }
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).isNotEqualTo(requests.get(1));
        var written = ArgumentCaptor.forClass(IndexedVersion.class);
        verify(index, times(2)).upsertChunks(written.capture(), anyString());
        for (int i = 0; i < 2; i++) {
            var version = written.getAllValues().get(i);
            assertThat(version.children()).extracting(com.kwiki.indexing.chunk.ChildChunk::content).isEqualTo(requests.get(i));
            assertThat(version.childVectors()).hasSameSizeAs(version.children());
            for (int j = 0; j < version.children().size(); j++) {
                assertThat(version.childVectors().get(j)).containsExactly((float) version.children().get(j).content().hashCode(), 0, 0, 0);
            }
        }
        verify(jdbc).update(contains("indexing_job_target SET state = 'COMPLETED'"), eq(54L));
        verify(jdbc).update(contains("indexing_job_target SET state = 'COMPLETED'"), eq(55L));
    }

    private record FakeChunker(String id, int childCount) implements com.kwiki.indexing.chunk.plugin.ChunkerPlugin {
        public String label() { return id; }
        public List<com.kwiki.indexing.chunk.ParentChunk> parents(String prefix, com.kwiki.indexing.parse.StructuredDocument document) {
            return List.of(new com.kwiki.indexing.chunk.ParentChunk(prefix + ":P0", 0, List.of(), 0, 1,
                    id + document.plainText(), com.kwiki.indexing.chunk.ParentChunk.BoundaryType.HARD));
        }
        public List<com.kwiki.indexing.chunk.ChildChunk> children(com.kwiki.indexing.chunk.ParentChunk parent,
                com.kwiki.indexing.parse.StructuredDocument document) {
            return java.util.stream.IntStream.range(0, childCount).mapToObj(i -> new com.kwiki.indexing.chunk.ChildChunk(
                    parent.parentKey() + ":C" + i, i, parent.parentKey(), 0, 1, parent.content() + i,
                    com.kwiki.indexing.chunk.ChildChunk.BoundaryType.HARD)).toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        return (org.springframework.beans.factory.ObjectProvider<T>)
                new org.springframework.beans.factory.ObjectProvider<Object>() {
                    @Override
                    public Object getIfAvailable() {
                        return value;
                    }
                };
    }
}
