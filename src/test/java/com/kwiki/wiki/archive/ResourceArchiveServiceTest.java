package com.kwiki.wiki.archive;

import com.kwiki.indexing.job.IndexingJobEnqueuer;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.access.ScopeVersionService;
import com.kwiki.wiki.domain.ArchiveBatch;
import com.kwiki.wiki.domain.ArchiveBatchItem;
import com.kwiki.wiki.domain.KnowledgeBase;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.persistence.ArchiveBatchItemRepository;
import com.kwiki.wiki.persistence.ArchiveBatchRepository;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseRepository;
import com.kwiki.wiki.persistence.SourceDocumentRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 带可控时钟与模拟持久化的回收站生命周期：
 * 带保留窗口的子树分批、幂等的重复归档保留
 * 原定时器、恢复时的过期/410 与冲突语义、知识库优先的
 * 约束，以及原父节点已消失时的根节点重定位。
 */
class ResourceArchiveServiceTest {

    static final CurrentUser OWNER = new CurrentUser(9L, "owner", false);
    static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    WikiPageRepository pages;
    KnowledgeBaseRepository knowledgeBases;
    AttachmentRepository attachments;
    SourceDocumentRepository sources;
    ArchiveBatchRepository batches;
    ArchiveBatchItemRepository items;
    KnowledgeBaseAuthorizationService authorization;
    ResourceAuthorizationService resources;
    ScopeVersionService versions;
    IndexingJobEnqueuer indexingJobs;
    ArchiveIndexSyncService indexSync;
    TransactionRunner transactions;
    ResourceArchiveService service;

    @BeforeEach
    void setUp() {
        pages = Mockito.mock(WikiPageRepository.class);
        knowledgeBases = Mockito.mock(KnowledgeBaseRepository.class);
        attachments = Mockito.mock(AttachmentRepository.class);
        sources = Mockito.mock(SourceDocumentRepository.class);
        batches = Mockito.mock(ArchiveBatchRepository.class);
        items = Mockito.mock(ArchiveBatchItemRepository.class);
        authorization = Mockito.mock(KnowledgeBaseAuthorizationService.class);
        resources = Mockito.mock(ResourceAuthorizationService.class);
        versions = Mockito.mock(ScopeVersionService.class);
        indexingJobs = Mockito.mock(IndexingJobEnqueuer.class);
        indexSync = Mockito.mock(ArchiveIndexSyncService.class);
        transactions = new TransactionRunner(null);
        AtomicLong ids = new AtomicLong(100);
        lenient().when(batches.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(batches.findById(anyLong())).thenReturn(Optional.empty());
        lenient().when(items.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(indexSync.attemptSync(any())).thenReturn(ArchiveBatch.SYNC_SYNCED);
        service = new ResourceArchiveService(pages, knowledgeBases, attachments, sources,
                batches, items, authorization, resources, versions, indexingJobs, indexSync,
                transactions, jdbcProvider(null),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<org.springframework.jdbc.core.JdbcOperations> jdbcProvider(
            org.springframework.jdbc.core.JdbcOperations value) {
        return new ObjectProvider() {
            @Override
            public Object getIfAvailable() {
                return value;
            }
        };
    }

    private WikiPage page(long id, long kbId, Long parentId, String status) {
        WikiPage page = new WikiPage("u" + id, kbId, parentId, "页面" + id,
                WikiPage.TYPE_PAGE, 0, OWNER.id());
        org.springframework.test.util.ReflectionTestUtils.setField(page, "id", id);
        org.springframework.test.util.ReflectionTestUtils.setField(page, "status", status);
        return page;
    }

    private KnowledgeBase kb(long id) {
        KnowledgeBase base = new KnowledgeBase("kb-" + id, "知识库" + id, null, OWNER.id());
        org.springframework.test.util.ReflectionTestUtils.setField(base, "id", id);
        return base;
    }

    private void stubArchiveSave(long batchId) {
        when(batches.save(any())).thenAnswer(inv -> {
            ArchiveBatch batch = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(batch, "id", batchId);
            when(batches.findById(batchId)).thenReturn(Optional.of(batch));
            return batch;
        });
    }

    @Test
    void pageArchiveSnapshotsTheActiveSubtreeIntoOneRetainedBatch() {
        var root = page(1, 5, null, WikiPage.STATUS_ACTIVE);
        var child = page(2, 5, 1L, WikiPage.STATUS_ACTIVE);
        var grandChild = page(3, 5, 2L, WikiPage.STATUS_ACTIVE);
        var archivedMiddle = page(4, 5, 1L, WikiPage.STATUS_ARCHIVED); // 历史遗留的分支中途
        when(knowledgeBases.findById(5L)).thenReturn(Optional.of(kb(5)));
        when(pages.findByIdAndStatus(1L, WikiPage.STATUS_ACTIVE)).thenReturn(Optional.of(root));
        when(pages.findByKbId(5L)).thenReturn(List.of(root, child, grandChild, archivedMiddle));
        when(sources.findByPageIdIn(any())).thenReturn(List.of());
        stubArchiveSave(77L);

        var result = service.archivePage(OWNER, 5, 1);

        assertThat(result.itemCount()).isEqualTo(3); // 仅 ACTIVE 子树
        assertThat(result.purgeAfter()).isEqualTo(NOW.plus(168, ChronoUnit.HOURS));
        assertThat(result.indexSyncStatus()).isEqualTo(ArchiveBatch.SYNC_SYNCED);
        assertThat(root.isArchived() && child.isArchived() && grandChild.isArchived()).isTrue();
        verify(indexingJobs, atLeastOnce()).enqueuePageDelete(eq(1L), anyLong());
        verify(indexingJobs, atLeastOnce()).enqueuePageDelete(eq(2L), anyLong());
        verify(indexingJobs, atLeastOnce()).enqueuePageDelete(eq(3L), anyLong());
        verify(versions).bump(5L);
    }

    @Test
    void rearchiveIsIdempotentAndNeverResetsTheTimer() {
        var root = page(1, 5, null, WikiPage.STATUS_ACTIVE);
        when(knowledgeBases.findById(5L)).thenReturn(Optional.of(kb(5)));
        when(pages.findByIdAndStatus(1L, WikiPage.STATUS_ACTIVE)).thenReturn(Optional.of(root));
        when(pages.findByKbId(5L)).thenReturn(List.of(root));
        when(sources.findByPageIdIn(any())).thenReturn(List.of());
        var existing = new ArchiveBatch("uuid", ArchiveBatch.SCOPE_PAGE, 1, 5, OWNER.id(),
                NOW.minus(100, ChronoUnit.HOURS), NOW.plus(68, ChronoUnit.HOURS), 1,
                ArchiveBatch.ORIGIN_NORMAL);
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", 42L);
        when(batches.findFirstByScopeTypeAndRootResourceIdAndStateNotOrderByArchivedAtDesc(
                eq(ArchiveBatch.SCOPE_PAGE), eq(1L), eq(ArchiveBatch.STATE_PURGED)))
                .thenReturn(Optional.of(existing));

        var result = service.archivePage(OWNER, 5, 1);

        assertThat(result.batchId()).isEqualTo(42L);
        // 原窗口（now+68h），不会重新锚定到固定时钟的 now+168h
        assertThat(result.purgeAfter()).isEqualTo(NOW.plus(68, ChronoUnit.HOURS));
        verify(batches, never()).save(any(ArchiveBatch.class));
    }

    @Test
    void restoreAfterExpiryIsRejectedAsGone() {
        var batch = new ArchiveBatch("uuid", ArchiveBatch.SCOPE_PAGE, 1, 5, OWNER.id(),
                NOW.minus(200, ChronoUnit.HOURS), NOW.minus(32, ChronoUnit.HOURS), 1,
                ArchiveBatch.ORIGIN_NORMAL);
        org.springframework.test.util.ReflectionTestUtils.setField(batch, "id", 42L);
        when(batches.findById(42L)).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.restore(OWNER, 42L))
                .isInstanceOf(ResourceArchiveService.ExpiredBatchException.class)
                .hasMessageContaining("保留期已过");
    }

    @Test
    void restoringAPageInsideAnArchivedKbRequiresKbFirst() {
        var batch = new ArchiveBatch("uuid", ArchiveBatch.SCOPE_PAGE, 1, 5, OWNER.id(),
                NOW, NOW.plus(100, ChronoUnit.HOURS), 1, ArchiveBatch.ORIGIN_NORMAL);
        org.springframework.test.util.ReflectionTestUtils.setField(batch, "id", 42L);
        when(batches.findById(42L)).thenReturn(Optional.of(batch));
        var archivedKb = kb(5);
        archivedKb.archive();
        when(knowledgeBases.findById(5L)).thenReturn(Optional.of(archivedKb));
        when(items.findByBatchIdOrderByIdAsc(42L)).thenReturn(List.of());
        service = new ResourceArchiveService(pages, knowledgeBases, attachments, sources,
                batches, items, authorization, resources, versions, indexingJobs, indexSync,
                transactions, jdbcProvider(null), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.restore(OWNER, 42L))
                .isInstanceOf(ResourceArchiveService.BatchConflictException.class)
                .hasMessageContaining("先恢复知识库");
    }

    @Test
    void restoreRelocatesToKbRootWhenPriorParentIsInvalid() {
        var batch = new ArchiveBatch("uuid", ArchiveBatch.SCOPE_PAGE, 2, 5, OWNER.id(),
                NOW, NOW.plus(100, ChronoUnit.HOURS), 1, ArchiveBatch.ORIGIN_NORMAL);
        org.springframework.test.util.ReflectionTestUtils.setField(batch, "id", 42L);
        when(batches.findById(42L)).thenReturn(Optional.of(batch));
        when(knowledgeBases.findById(5L)).thenReturn(Optional.of(kb(5)));
        var item = new ArchiveBatchItem(42L, ArchiveBatchItem.RESOURCE_PAGE, 2, 5,
                ArchiveBatchItem.PRIOR_ACTIVE, 999L, 1);
        org.springframework.test.util.ReflectionTestUtils.setField(item, "id", 1L);
        when(items.findByBatchIdOrderByIdAsc(42L)).thenReturn(List.of(item));
        var archived = page(2, 5, 999L, WikiPage.STATUS_ARCHIVED);
        when(pages.findById(2L)).thenReturn(Optional.of(archived));
        when(pages.findByIdAndStatus(999L, WikiPage.STATUS_ACTIVE)).thenReturn(Optional.empty());
        when(pages.findByKbIdAndParentIdAndStatusOrderBySiblingOrderAsc(5L, null,
                WikiPage.STATUS_ACTIVE)).thenReturn(List.of());

        var result = service.restore(OWNER, 42L);

        assertThat(result.relocatedToRoot()).isEqualTo(1);
        assertThat(archived.getParentId()).isNull(); // 移动到知识库根节点
        assertThat(archived.isArchived()).isFalse();
        verify(indexingJobs, never()).enqueuePageUpsert(anyLong(), anyLong(), anyLong());
        // 没有已发布修订版本：不会有任何内容被重新索引
    }

    @Test
    void restoreRequeuesOnlyPublishedRevisions() {
        var batch = new ArchiveBatch("uuid", ArchiveBatch.SCOPE_PAGE, 2, 5, OWNER.id(),
                NOW, NOW.plus(100, ChronoUnit.HOURS), 1, ArchiveBatch.ORIGIN_NORMAL);
        org.springframework.test.util.ReflectionTestUtils.setField(batch, "id", 42L);
        when(batches.findById(42L)).thenReturn(Optional.of(batch));
        when(knowledgeBases.findById(5L)).thenReturn(Optional.of(kb(5)));
        var item = new ArchiveBatchItem(42L, ArchiveBatchItem.RESOURCE_PAGE, 2, 5,
                ArchiveBatchItem.PRIOR_ACTIVE, null, 1);
        org.springframework.test.util.ReflectionTestUtils.setField(item, "id", 1L);
        when(items.findByBatchIdOrderByIdAsc(42L)).thenReturn(List.of(item));
        var archived = page(2, 5, null, WikiPage.STATUS_ARCHIVED);
        archived.setCurrentPublishedRevisionId(88L);
        when(pages.findById(2L)).thenReturn(Optional.of(archived));

        service.restore(OWNER, 42L);

        verify(indexingJobs).enqueuePageUpsert(2L, 88L, 2L);
        verify(versions).bump(5L);
    }

    @Test
    void kbArchiveCoversActivePagesStoredAttachmentsAndItself() {
        var kbRow = kb(5);
        when(knowledgeBases.findById(5L)).thenReturn(Optional.of(kbRow));
        when(pages.findByKbIdAndStatusOrderByParentIdAscSiblingOrderAsc(5L,
                WikiPage.STATUS_ACTIVE)).thenReturn(List.of(page(1, 5, null, WikiPage.STATUS_ACTIVE)));
        var attachment = new com.kwiki.wiki.domain.Attachment("a1", 5L, OWNER.id(), "f.pdf",
                "application/pdf", 10);
        org.springframework.test.util.ReflectionTestUtils.setField(attachment, "id", 33L);
        attachment.markStored(500L);
        when(attachments.findByKbIdAndStatusOrderByIdDesc(5L,
                com.kwiki.wiki.domain.Attachment.STATUS_STORED)).thenReturn(List.of(attachment));
        stubArchiveSave(90L);

        var result = service.archiveKnowledgeBase(OWNER, 5);

        assertThat(kbRow.isArchived()).isTrue();
        assertThat(result.itemCount()).isEqualTo(3); // 知识库 + 1 个页面 + 1 个附件
        verify(indexingJobs).enqueueKnowledgeBaseDelete(5L, 2L); // 递增后的版本
        verify(indexingJobs).enqueueAttachmentDelete(33L, 2L);
    }
}
