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
import com.kwiki.infrastructure.config.ExternalServicesProperties;
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
        return new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default",
                "text-embedding-v4", dims, 1);
    }

    @BeforeEach
    void setUp() {
        ExternalServicesProperties external = new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter("http://cc", "t", null, null,
                        Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new ExternalServicesProperties.Elasticsearch(null, null, null),
                new ExternalServicesProperties.AnswerLlm("http://l/v1", "k", "m",
                        Duration.ofSeconds(10)),
                new ExternalServicesProperties.QwenEmbedding("http://q/v1", "k",
                        "text-embedding-v4", 4, Duration.ofSeconds(10)),
                ExternalServicesProperties.unusedVisionModel());
        IndexingProperties indexing = new IndexingProperties(
                List.of(
                        new IndexingProperties.Manifest("gen-4", "kwiki-parse-1", "kwiki-chunk-1",
                                "default", "text-embedding-v4", 4, 1),
                        new IndexingProperties.Manifest("gen-6", "kwiki-parse-1", "kwiki-chunk-1",
                                "alt-dims", "text-embedding-v4", 6, 1)),
                Map.of("alt-dims", new IndexingProperties.EmbeddingProfile(
                        "https://alt-embed/v1", "sk-alt", Duration.ofSeconds(30))),
                new IndexingProperties.Rebuild(50, 2, 20),
                new IndexingProperties.Catchup(100, Duration.ofSeconds(30)),
                new IndexingProperties.Capacity(20, 8, Duration.ofSeconds(5)),
                new IndexingProperties.Management(false));
        VersionedIndexingPipelineRegistry registry = new VersionedIndexingPipelineRegistry(
                indexing, external,
                StandardTestProperties.providerOf(DocumentParseService.forTests()),
                StandardTestProperties.providerOf(embeddings),
                StandardTestProperties.nullProvider());

        registry.registerProfileClientForTests("alt-dims", embeddings);
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
