package com.kwiki.indexing.job;

import com.kwiki.infrastructure.config.ExternalServicesProperties;
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
import com.kwiki.wiki.domain.Attachment;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 目标级执行契约：worker 把带版本号的内容写入目标行记录的显式物理
 * 索引（绝不经过读别名），确定性 chunk key 保证重放安全，失败按目标
 * 独立退避。影子目标的物理名与目标版本取自认领返回的行。
 */
@ExtendWith(MockitoExtension.class)
class IndexingWorkerTest {

    private static final long KB = 1L;
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PHYSICAL = "kwiki-chunks-v2";

    @Mock
    IndexingJobTargetClaimer claimer;

    @Mock
    JdbcOperations jdbc;

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

    private IndexingJobTargetStore targets;
    private IndexingWorker worker;

    static EditableIndexConfig builtConfig() {
        return new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default",
                "text-embedding-v4", 4, 1);
    }

    @BeforeEach
    void setUp() {
        targets = new IndexingJobTargetStore(provider(jdbc));
        VersionedIndexingPipelineRegistry registry = new VersionedIndexingPipelineRegistry(
                emptyManifests(), properties(),
                StandardTestProperties.providerOf(DocumentParseService.forTests()),
                StandardTestProperties.providerOf(embeddings),
                StandardTestProperties.nullProvider());
        SearchIndexVersion v2 = SearchIndexVersion.bootstrapped(
                2, PHYSICAL, builtConfig(), "hash");
        lenient().when(versionRepository.findByVersionNumber(2)).thenReturn(Optional.of(v2));
        worker = new IndexingWorker(claimer, targets, registry,
                StandardTestProperties.providerOf(versionRepository),
                index, null, pages, knowledgeBases, revisions, attachments,
                storage, new SimpleMeterRegistry(), 8, 30, 3600);
    }

    private static IndexingProperties emptyManifests() {
        return new IndexingProperties(null, null,
                new IndexingProperties.Rebuild(50, 2, 20),
                new IndexingProperties.Catchup(100, java.time.Duration.ofSeconds(30)),
                new IndexingProperties.Capacity(20, 8, java.time.Duration.ofSeconds(5)),
                new IndexingProperties.Management(false));
    }

    private static ExternalServicesProperties properties() {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter("http://cc:8080", "kapp-test",
                        null, null, Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new ExternalServicesProperties.Elasticsearch(null, null, null),
                new ExternalServicesProperties.AnswerLlm("http://l/v1", "k", "m", Duration.ofSeconds(10)),
                new ExternalServicesProperties.QwenEmbedding("http://q/v1", "k",
                        "text-embedding-v4", 4, Duration.ofSeconds(10)),
                ExternalServicesProperties.unusedVisionModel());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        return (ObjectProvider<T>) new ObjectProvider<Object>() {
            @Override
            public Object getIfAvailable() {
                return value;
            }
        };
    }

    private static Map<String, Object> targetRow(long targetId, String type, String resourceType,
                                                 long resourceId, Long revisionId) {
        Map<String, Object> row = new HashMap<>();
        row.put("target_id", targetId);
        row.put("job_id", 100L + targetId);
        row.put("event_id", null);
        row.put("target_version", 2);
        row.put("physical_name", PHYSICAL);
        row.put("target_state", "LEASED");
        row.put("attempts", 1);
        row.put("job_type", type);
        row.put("resource_type", resourceType);
        row.put("resource_id", resourceId);
        row.put("revision_id", revisionId);
        row.put("expected_lifecycle_version", null);
        return row;
    }

    private void stubPageResource(long pageId, long revisionId, String markdown) {
        WikiPage page = new WikiPage("uuid-" + pageId, KB, null, "工程手册",
                WikiPage.TYPE_PAGE, 0, 1L);
        org.springframework.test.util.ReflectionTestUtils.setField(page, "id", pageId);
        lenient().when(pages.findById(pageId)).thenReturn(Optional.of(page));
        WikiPageRevision revision = new WikiPageRevision(pageId, 3, markdown,
                markdown, "note", 1L);
        org.springframework.test.util.ReflectionTestUtils.setField(revision, "id", revisionId);
        lenient().when(revisions.findById(revisionId)).thenReturn(Optional.of(revision));
        lenient().when(embeddings.embed(any()))
                .thenAnswer(inv -> ((List<String>) inv.getArgument(0)).stream()
                        .map(text -> new float[4]).toList());
        lenient().when(jdbc.queryForList(anyString(), eq(Long.class), eq(1L)))
                .thenReturn(List.of(101L));
    }

    @Test
    void pageUpsertWritesTheRecordedPhysicalIndexWithItsVersion() {
        stubPageResource(7L, 103L, "# 标题\n\n" + "内容甲".repeat(100));

        worker.process(targetRow(1L, "UPSERT", "PAGE", 7L, 103L), new IndexingWorker.JobIntermediates());

        ArgumentCaptor<IndexedVersion> version = ArgumentCaptor.forClass(IndexedVersion.class);
        verify(index).upsertChunks(version.capture(), eq(PHYSICAL));
        IndexedVersion captured = version.getValue();
        assertThat(captured.resourceType()).isEqualTo("PAGE");
        assertThat(captured.revisionId()).isEqualTo(103L);
        assertThat(captured.indexVersion()).isEqualTo(2);
        assertThat(captured.embeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(captured.childVectors()).hasSize(captured.children().size());
        assertThat(captured.children()).allSatisfy(child ->
                assertThat(child.parentKey()).startsWith("PAGE:7:103:P"));
        // 目标级完成：目标行进入 COMPLETED
        verify(jdbc).update(contains("indexing_job_target SET state = 'COMPLETED'"), eq(1L));
    }

    @Test
    void crashAfterWriteReplaysIdenticalChunkSet() {
        stubPageResource(7L, 103L, "# 标题\n\n" + "内容乙".repeat(120));

        worker.process(targetRow(1L, "UPSERT", "PAGE", 7L, 103L), new IndexingWorker.JobIntermediates());
        worker.process(targetRow(1L, "UPSERT", "PAGE", 7L, 103L), new IndexingWorker.JobIntermediates());

        ArgumentCaptor<IndexedVersion> versions = ArgumentCaptor.forClass(IndexedVersion.class);
        verify(index, org.mockito.Mockito.times(2)).upsertChunks(versions.capture(), eq(PHYSICAL));
        IndexedVersion first = versions.getAllValues().get(0);
        IndexedVersion second = versions.getAllValues().get(1);
        // float[] 没有值相等性，因此显式比较确定性部分
        assertThat(second.parents()).isEqualTo(first.parents());
        assertThat(second.children()).isEqualTo(first.children());
        assertThat(second.childVectors()).hasSameSizeAs(first.childVectors());
        for (int i = 0; i < first.childVectors().size(); i++) {
            assertThat(java.util.Arrays.equals(second.childVectors().get(i),
                    first.childVectors().get(i)))
                    .as("vector %d identical across replays", i).isTrue();
        }
        assertThat(second.revisionId()).isEqualTo(first.revisionId());
    }

    @Test
    void persistentFailureBacksOffTheTargetAlone() {
        stubPageResource(7L, 103L, "# 标题\n\n正文");
        org.mockito.Mockito.doThrow(new RuntimeException("es node down"))
                .when(index).upsertChunks(any(), anyString());

        worker.process(targetRow(1L, "UPSERT", "PAGE", 7L, 103L), new IndexingWorker.JobIntermediates());

        verify(jdbc).update(contains("'RETRY_WAIT'"), anyInt(), anyString(), anyString(),
                anyInt(), anyLong(), anyLong(), eq(1L));
        verify(jdbc, never()).update(contains("indexing_job_target SET state = 'COMPLETED'"),
                anyLong());
    }

    @Test
    void nonImageAttachmentUpsertClearsLegacyChunksInsteadOfIndexing() {
        Attachment attachment = new Attachment("att-uuid", KB, 1L, "spec.docx", DOCX, 10);
        org.springframework.test.util.ReflectionTestUtils.setField(attachment, "id", 21L);
        attachment.markStored(31L);
        when(attachments.findById(21L)).thenReturn(Optional.of(attachment));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(1L))).thenReturn(List.of(101L));

        worker.process(targetRow(1L, "UPSERT", "ATTACHMENT", 21L, null), new IndexingWorker.JobIntermediates());

        verify(index, never()).upsertChunks(any(), anyString());
        verify(embeddings, never()).embed(any());
        verify(storage, never()).readContent(anyLong());
        // 旧分块从该目标的物理索引中移除；附件本身保持只显示状态
        verify(index).deleteResourceChunks(PHYSICAL, "ATTACHMENT", 21L);
        verify(jdbc).update(contains("indexing_job_target SET state = 'COMPLETED'"), eq(1L));
    }

    @Test
    void pendingAttachmentWithoutFileIdIsSkippedWithoutReads() {
        Attachment pending = new Attachment("att-pending", KB, 1L, "spec.docx", DOCX, 10);
        org.springframework.test.util.ReflectionTestUtils.setField(pending, "id", 22L);
        when(attachments.findById(22L)).thenReturn(Optional.of(pending));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(1L))).thenReturn(List.of(101L));

        worker.process(targetRow(1L, "UPSERT", "ATTACHMENT", 22L, null), new IndexingWorker.JobIntermediates());

        verify(storage, never()).readContent(anyLong());
        verify(index, never()).upsertChunks(any(), anyString());
        verify(jdbc).update(contains("indexing_job_target SET state = 'COMPLETED'"), eq(1L));
    }

    @Test
    void transientImageStorageFailureSchedulesRetryWithoutCompleting() {
        Attachment attachment = new Attachment("att-img", KB, 1L, "arch.png", "image/png", 10);
        org.springframework.test.util.ReflectionTestUtils.setField(attachment, "id", 23L);
        attachment.markStored(33L);
        when(attachments.findById(23L)).thenReturn(Optional.of(attachment));
        when(storage.readContent(33L)).thenThrow(new com.kwiki.wiki.attach.AttachmentStorageException(
                com.kwiki.wiki.attach.AttachmentStorageException.Category.TRANSIENT,
                "content fetch failed (HTTP 503)"));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(1L))).thenReturn(List.of(101L));

        worker.process(targetRow(1L, "UPSERT", "ATTACHMENT", 23L, null), new IndexingWorker.JobIntermediates());

        verify(jdbc).update(contains("'RETRY_WAIT'"), anyInt(), anyString(), anyString(),
                anyInt(), anyLong(), anyLong(), eq(1L));
        verify(jdbc, never()).update(contains("indexing_job_target SET state = 'COMPLETED'"),
                anyLong());
    }

    @Test
    void permanentImageStorageFailureFailsTerminallyWithoutRetries() {
        Attachment attachment = new Attachment("att-img", KB, 1L, "huge.png", "image/png", 10);
        org.springframework.test.util.ReflectionTestUtils.setField(attachment, "id", 24L);
        attachment.markStored(34L);
        when(attachments.findById(24L)).thenReturn(Optional.of(attachment));
        when(storage.readContent(34L)).thenThrow(new com.kwiki.wiki.attach.AttachmentStorageException(
                com.kwiki.wiki.attach.AttachmentStorageException.Category.PERMANENT,
                "content exceeds the configured attachment size limit"));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(1L))).thenReturn(List.of(101L));

        worker.process(targetRow(1L, "UPSERT", "ATTACHMENT", 24L, null), new IndexingWorker.JobIntermediates());

        verify(index, never()).upsertChunks(any(), anyString());
        verify(jdbc).update(contains("'FAILED'"), eq(0), anyString(), anyString(),
                eq(0), anyLong(), anyLong(), eq(1L));
    }

    @Test
    void deleteJobRemovesAllResourceChunksFromThePhysicalIndex() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(5L))).thenReturn(List.of(105L));

        worker.process(targetRow(5L, "DELETE", "PAGE", 7L, null), new IndexingWorker.JobIntermediates());

        verify(index).deleteResourceChunks(PHYSICAL, "PAGE", 7L);
        verify(jdbc).update(contains("indexing_job_target SET state = 'COMPLETED'"), eq(5L));
    }

    @Test
    void missingPhysicalTargetVersionIsRejectedBeforeWriting() {
        stubPageResource(7L, 103L, "# 标题\n\n正文");
        Map<String, Object> row = targetRow(9L, "UPSERT", "PAGE", 7L, 103L);
        row.put("target_version", 0);

        worker.process(row, new IndexingWorker.JobIntermediates());

        verify(index, never()).upsertChunks(any(), anyString());
        verify(jdbc).update(contains("'RETRY_WAIT'"), anyInt(), anyString(), anyString(),
                anyInt(), anyLong(), anyLong(), eq(9L));
    }

    private static String contains(String fragment) {
        return org.mockito.ArgumentMatchers.contains(fragment);
    }
}
