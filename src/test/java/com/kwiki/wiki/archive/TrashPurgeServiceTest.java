package com.kwiki.wiki.archive;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.domain.ArchiveBatch;
import com.kwiki.wiki.persistence.ArchiveBatchRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

class TrashPurgeServiceTest {

    @Test
    void checksTheArchiveScopeBeforeDelegatingPermanentPurge() {
        ArchiveBatchRepository batches = mock(ArchiveBatchRepository.class);
        KnowledgeBaseAuthorizationService authorization = mock(KnowledgeBaseAuthorizationService.class);
        RecycleBinCleanupService cleanup = mock(RecycleBinCleanupService.class);
        ArchiveBatch batch = new ArchiveBatch("batch-42", ArchiveBatch.SCOPE_PAGE, 7L, 5L,
                9L, Instant.parse("2026-09-14T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"), 1, ArchiveBatch.ORIGIN_NORMAL);
        org.springframework.test.util.ReflectionTestUtils.setField(batch, "id", 42L);
        when(batches.findById(42L)).thenReturn(Optional.of(batch));
        when(cleanup.purgeNow(42L)).thenReturn(new RecycleBinCleanupService.PurgeResult(42L, 1));

        TrashPurgeService service = new TrashPurgeService(batches, authorization, cleanup);
        var result = service.purge(new CurrentUser(9L, "owner", false), 42L);

        verify(authorization).require(eq(new CurrentUser(9L, "owner", false)), eq(5L),
                eq(com.kwiki.wiki.access.WikiAction.ARCHIVE_PAGE));
        verify(cleanup).purgeNow(42L);
        assertThat(result.batchId()).isEqualTo(42L);
        assertThat(result.purgedItems()).isEqualTo(1);
        assertThat(result.message()).contains("不可恢复");
    }

    @Test
    void batchPurgeAuthorizesEverythingThenDeletesPagesBeforeKnowledgeBases() {
        ArchiveBatchRepository batches = mock(ArchiveBatchRepository.class);
        KnowledgeBaseAuthorizationService authorization = mock(KnowledgeBaseAuthorizationService.class);
        RecycleBinCleanupService cleanup = mock(RecycleBinCleanupService.class);
        ArchiveBatch kb = new ArchiveBatch("batch-10", ArchiveBatch.SCOPE_KNOWLEDGE_BASE,
                5L, 5L, 9L, Instant.parse("2026-09-14T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"), 3, ArchiveBatch.ORIGIN_NORMAL);
        ArchiveBatch page = new ArchiveBatch("batch-11", ArchiveBatch.SCOPE_PAGE,
                7L, 5L, 9L, Instant.parse("2026-09-14T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"), 1, ArchiveBatch.ORIGIN_NORMAL);
        org.springframework.test.util.ReflectionTestUtils.setField(kb, "id", 10L);
        org.springframework.test.util.ReflectionTestUtils.setField(page, "id", 11L);
        when(batches.findById(10L)).thenReturn(Optional.of(kb));
        when(batches.findById(11L)).thenReturn(Optional.of(page));
        when(cleanup.purgeNow(11L)).thenReturn(new RecycleBinCleanupService.PurgeResult(11L, 1));
        when(cleanup.purgeNow(10L)).thenReturn(new RecycleBinCleanupService.PurgeResult(10L, 3));

        TrashPurgeService service = new TrashPurgeService(batches, authorization, cleanup);
        var result = service.purgeAll(new CurrentUser(9L, "owner", false),
                List.of(10L, 11L, 10L));

        var order = inOrder(authorization, cleanup);
        order.verify(authorization).require(eq(new CurrentUser(9L, "owner", false)), eq(5L),
                eq(com.kwiki.wiki.access.WikiAction.ARCHIVE_KNOWLEDGE_BASE));
        order.verify(authorization).require(eq(new CurrentUser(9L, "owner", false)), eq(5L),
                eq(com.kwiki.wiki.access.WikiAction.ARCHIVE_PAGE));
        order.verify(cleanup).purgeNow(11L);
        order.verify(cleanup).purgeNow(10L);
        assertThat(result.deletedBatches()).isEqualTo(2);
        assertThat(result.purgedItems()).isEqualTo(4);
    }
}
