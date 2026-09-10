package com.kwiki.indexing.job;

import com.kwiki.infrastructure.config.ExternalServicesProperties;
import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.indexing.pipeline.ChunkIndexPort;
import com.kwiki.indexing.pipeline.IndexedVersion;
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

@ExtendWith(MockitoExtension.class)
class IndexingWorkerTest {

    private static final long KB = 1L;
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Mock
    IndexingJobClaimer claimer;

    @Mock
    JdbcOperations jdbc;

    @Mock
    ChunkEmbeddingPort embeddings;

    @Mock
    ChunkIndexPort index;

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

    private IndexingJobStore store;
    private IndexingWorker worker;

    @BeforeEach
    void setUp() {
        store = new IndexingJobStore(provider(jdbc));
        worker = new IndexingWorker(claimer, store, DocumentParseService.forTests(),
                new com.kwiki.indexing.chunk.ParentChunker(),
                new com.kwiki.indexing.chunk.ChildChunker(),
                embeddings, index, null, pages, knowledgeBases, revisions, attachments,
                storage, properties(), new SimpleMeterRegistry(), 8, 30, 3600);
    }

    private static ExternalServicesProperties properties() {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter("http://cc:8080", "kapp-test",
                        null, null, Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new ExternalServicesProperties.Elasticsearch(null, null, null),
                new ExternalServicesProperties.AnswerLlm("http://l/v1", "k", "m", Duration.ofSeconds(10)),
                new ExternalServicesProperties.QwenEmbedding("http://q/v1", "k",
                        "text-embedding-v4", 4, Duration.ofSeconds(10)));
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

    private static Map<String, Object> jobRow(long id, String type, String resourceType,
                                              long resourceId, Long revisionId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("job_type", type);
        row.put("resource_type", resourceType);
        row.put("resource_id", resourceId);
        row.put("revision_id", revisionId);
        row.put("state", "LEASED");
        return row;
    }

    private void stubPageJob(long pageId, long revisionId, String markdown) {
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
    }

    @Test
    void pageUpsertIndexesDeterministicVersionAndCompletes() {
        stubPageJob(7L, 103L, "# 标题\n\n" + "内容甲".repeat(100));
        lenient().when(jdbc.queryForList(anyString(), eq(7L == 0 ? 1L : 1L)))
                .thenReturn(List.of(jobRow(1, "UPSERT", "PAGE", 7L, 103L)));

        worker.process(1L);

        ArgumentCaptor<IndexedVersion> version = ArgumentCaptor.forClass(IndexedVersion.class);
        verify(index).upsertChunks(version.capture());
        IndexedVersion captured = version.getValue();
        assertThat(captured.resourceType()).isEqualTo("PAGE");
        assertThat(captured.revisionId()).isEqualTo(103L);
        assertThat(captured.embeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(captured.childVectors()).hasSize(captured.children().size());
        assertThat(captured.children()).allSatisfy(child ->
                assertThat(child.parentKey()).startsWith("PAGE:7:103:P"));
        // 完成动作以 LEASED 状态为前置条件
        verify(jdbc).update(contains("state = 'COMPLETED'"), eq(1L));
    }

    @Test
    void crashAfterWriteReplaysIdenticalChunkSet() {
        stubPageJob(7L, 103L, "# 标题\n\n" + "内容乙".repeat(120));
        lenient().when(jdbc.queryForList(anyString(), anyLong()))
                .thenReturn(List.of(jobRow(1, "UPSERT", "PAGE", 7L, 103L)));

        worker.process(1L);
        worker.process(1L);

        ArgumentCaptor<IndexedVersion> versions = ArgumentCaptor.forClass(IndexedVersion.class);
        verify(index, org.mockito.Mockito.times(2)).upsertChunks(versions.capture());
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
    void persistentFailureUsesBackoffAndNeverCompletes() {
        stubPageJob(7L, 103L, "# 标题\n\n正文");
        when(jdbc.queryForList(anyString(), anyLong()))
                .thenReturn(List.of(jobRow(1, "UPSERT", "PAGE", 7L, 103L)));
        org.mockito.Mockito.doThrow(new RuntimeException("es node down"))
                .when(index).upsertChunks(any());

        worker.process(1L);

        verify(jdbc).update(contains("'RETRY_WAIT'"), anyInt(), anyString(), anyString(),
                anyInt(), anyLong(), anyLong(), eq(1L));
        verify(jdbc, never()).update(contains("state = 'COMPLETED'"), anyLong());
    }

    @Test
    void nonImageAttachmentUpsertClearsLegacyChunksInsteadOfIndexing() {
        Attachment attachment = new Attachment("att-uuid", KB, 1L, "spec.docx", DOCX, 10);
        org.springframework.test.util.ReflectionTestUtils.setField(attachment, "id", 21L);
        attachment.markStored(31L);
        when(attachments.findById(21L)).thenReturn(Optional.of(attachment));
        when(jdbc.queryForList(anyString(), anyLong()))
                .thenReturn(List.of(jobRow(1, "UPSERT", "ATTACHMENT", 21L, null)));

        worker.process(1L);

        verify(index, never()).upsertChunks(any());
        verify(embeddings, never()).embed(any());
        verify(storage, never()).readContent(anyLong());
        // 旧分块被移除；附件本身保持只显示状态
        verify(index).deleteResourceChunks("ATTACHMENT", 21L);
        verify(jdbc).update(contains("state = 'COMPLETED'"), eq(1L));
    }

    @Test
    void pendingAttachmentWithoutFileIdIsSkippedWithoutReads() {
        Attachment pending = new Attachment("att-pending", KB, 1L, "spec.docx", DOCX, 10);
        org.springframework.test.util.ReflectionTestUtils.setField(pending, "id", 22L);
        when(attachments.findById(22L)).thenReturn(Optional.of(pending));
        when(jdbc.queryForList(anyString(), anyLong()))
                .thenReturn(List.of(jobRow(1, "UPSERT", "ATTACHMENT", 22L, null)));

        worker.process(1L);

        verify(storage, never()).readContent(anyLong());
        verify(index, never()).upsertChunks(any());
        verify(jdbc).update(contains("state = 'COMPLETED'"), eq(1L));
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
        when(jdbc.queryForList(anyString(), anyLong()))
                .thenReturn(List.of(jobRow(1, "UPSERT", "ATTACHMENT", 23L, null)));

        worker.process(1L);

        verify(jdbc).update(contains("'RETRY_WAIT'"), anyInt(), anyString(), anyString(),
                anyInt(), anyLong(), anyLong(), eq(1L));
        verify(jdbc, never()).update(contains("state = 'COMPLETED'"), anyLong());
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
        when(jdbc.queryForList(anyString(), anyLong()))
                .thenReturn(List.of(jobRow(1, "UPSERT", "ATTACHMENT", 24L, null)));

        worker.process(1L);

        verify(index, never()).upsertChunks(any());
        verify(jdbc).update(contains("'FAILED'"), eq(0), anyString(), anyString(),
                eq(0), anyLong(), anyLong(), eq(1L));
    }

    @Test
    void deleteJobRemovesAllResourceChunks() {
        when(jdbc.queryForList(anyString(), anyLong()))
                .thenReturn(List.of(jobRow(5, "DELETE", "PAGE", 7L, null)));

        worker.process(5L);

        verify(index).deleteResourceChunks("PAGE", 7L);
        verify(jdbc).update(contains("state = 'COMPLETED'"), eq(5L));
    }

    private static String contains(String fragment) {
        return org.mockito.ArgumentMatchers.contains(fragment);
    }
}
