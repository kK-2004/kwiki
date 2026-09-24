package com.kwiki.wiki.archive;

import com.kwiki.indexing.search.ChunkIndexRepository;
import com.kwiki.indexing.version.IndexWriteTargets;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.domain.ArchiveBatch;
import com.kwiki.wiki.domain.ArchiveBatchItem;
import com.kwiki.wiki.persistence.ArchiveBatchItemRepository;
import com.kwiki.wiki.persistence.ArchiveBatchRepository;
import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecycleBinCleanupServiceTest {

    @Test
    void purgeNowDeletesAnUnexpiredBatchAndItsArchiveRows() {
        ArchiveBatchRepository batches = mock(ArchiveBatchRepository.class);
        ArchiveBatchItemRepository batchItems = mock(ArchiveBatchItemRepository.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        IndexWriteTargets writeTargets = mock(IndexWriteTargets.class);
        when(writeTargets.current()).thenReturn(List.of());

        ArchiveBatch batch = new ArchiveBatch("batch-42", ArchiveBatch.SCOPE_PAGE, 7L, 5L,
                9L, Instant.parse("2026-09-14T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"), 1, ArchiveBatch.ORIGIN_NORMAL);
        ReflectionTestUtils.setField(batch, "id", 42L);
        ArchiveBatchItem item = new ArchiveBatchItem(42L, ArchiveBatchItem.RESOURCE_PAGE, 7L,
                5L, ArchiveBatchItem.PRIOR_ACTIVE, null, 1L);
        ReflectionTestUtils.setField(item, "id", 99L);
        when(batches.findById(42L)).thenReturn(Optional.of(batch));
        when(batchItems.findByBatchIdOrderByIdAsc(42L)).thenReturn(List.of(item));
        when(jdbc.queryForObject(any(String.class), eq(Long.class), eq(42L))).thenReturn(42L);

        RecycleBinCleanupService service = new RecycleBinCleanupService(
                batches, batchItems, StandardTestProperties.providerOf(mock(ChunkIndexRepository.class)),
                writeTargets, new TransactionRunner(null), StandardTestProperties.providerOf(jdbc),
                20, Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC));

        var result = service.purgeNow(42L);

        assertThat(result.batchId()).isEqualTo(42L);
        assertThat(result.purgedItems()).isEqualTo(1);
        var indexingDeletion = inOrder(jdbc);
        indexingDeletion.verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("DELETE FROM indexing_job_target"),
                eq("PAGE"), eq(7L));
        indexingDeletion.verify(jdbc).update(
                eq("DELETE FROM indexing_job WHERE resource_type = ? AND resource_id = ?"),
                eq("PAGE"), eq(7L));
        verify(jdbc).update(eq("DELETE FROM archive_batch_item WHERE batch_id = ?"), eq(42L));
        verify(jdbc).update(eq("DELETE FROM archive_batch WHERE id = ?"), eq(42L));
    }

    @Test
    void purgeNowRefusesABatchThatWasAlreadyRestored() {
        ArchiveBatchRepository batches = mock(ArchiveBatchRepository.class);
        ArchiveBatchItemRepository batchItems = mock(ArchiveBatchItemRepository.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        ArchiveBatch batch = new ArchiveBatch("batch-42", ArchiveBatch.SCOPE_PAGE, 7L, 5L,
                9L, Instant.parse("2026-09-14T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"), 1, ArchiveBatch.ORIGIN_NORMAL);
        ReflectionTestUtils.setField(batch, "id", 42L);
        batch.markRestored();
        when(batches.findById(42L)).thenReturn(Optional.of(batch));
        when(jdbc.queryForObject(any(String.class), eq(Long.class), eq(42L))).thenReturn(42L);

        RecycleBinCleanupService service = new RecycleBinCleanupService(
                batches, batchItems, StandardTestProperties.nullProvider(),
                mock(IndexWriteTargets.class), new TransactionRunner(null),
                StandardTestProperties.providerOf(jdbc), 20,
                Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.purgeNow(42L))
                .isInstanceOf(ResourceArchiveService.BatchConflictException.class)
                .hasMessageContaining("已恢复");
    }

    @Test
    void purgeNowDeletesTheLastReferencedContentCenterFile() {
        ArchiveBatchRepository batches = mock(ArchiveBatchRepository.class);
        ArchiveBatchItemRepository batchItems = mock(ArchiveBatchItemRepository.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        AttachmentStorage storage = mock(AttachmentStorage.class);
        IndexWriteTargets writeTargets = mock(IndexWriteTargets.class);
        when(writeTargets.current()).thenReturn(List.of());

        ArchiveBatch batch = new ArchiveBatch("batch-42", ArchiveBatch.SCOPE_PAGE, 8L, 5L,
                9L, Instant.parse("2026-09-14T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"), 1, ArchiveBatch.ORIGIN_NORMAL);
        ReflectionTestUtils.setField(batch, "id", 42L);
        ArchiveBatchItem item = new ArchiveBatchItem(42L, ArchiveBatchItem.RESOURCE_ATTACHMENT,
                8L, 5L, ArchiveBatchItem.PRIOR_ACTIVE, null, 1L);
        ReflectionTestUtils.setField(item, "id", 99L);
        when(batches.findById(42L)).thenReturn(Optional.of(batch));
        when(batchItems.findByBatchIdOrderByIdAsc(42L)).thenReturn(List.of(item));
        when(jdbc.queryForObject(any(String.class), eq(Long.class), eq(42L))).thenReturn(42L);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("SELECT COUNT(*) FROM source_document"),
                eq(Integer.class), eq(8L))).thenReturn(0);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("SELECT COUNT(*) FROM page_revision_media"),
                eq(Integer.class), eq(8L))).thenReturn(0);
        when(jdbc.queryForObject(eq("SELECT blob_id FROM attachment WHERE id = ?"),
                eq(Long.class), eq(8L))).thenReturn(17L);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("COALESCE(ab.content_center_file_id"),
                eq(Long.class), eq(8L))).thenReturn(700L);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("attachment.id <> ?"),
                eq(Integer.class), eq(700L), eq(8L))).thenReturn(0);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("COUNT(*) FROM attachment WHERE blob_id = ?"),
                eq(Integer.class), eq(17L))).thenReturn(0);

        RecycleBinCleanupService service = new RecycleBinCleanupService(
                batches, batchItems, StandardTestProperties.nullProvider(), writeTargets,
                new TransactionRunner(null), StandardTestProperties.providerOf(jdbc),
                StandardTestProperties.providerOf(storage), 20,
                Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC));

        service.purgeNow(42L);

        var order = inOrder(storage, jdbc);
        order.verify(storage).delete(700L);
        order.verify(jdbc).update("DELETE FROM attachment WHERE id = ?", 8L);
        order.verify(jdbc).update("DELETE FROM attachment_blob WHERE id = ?", 17L);
    }

    @Test
    void purgeNowKeepsTheContentCenterFileWhenAnotherAttachmentReferencesIt() {
        ArchiveBatchRepository batches = mock(ArchiveBatchRepository.class);
        ArchiveBatchItemRepository batchItems = mock(ArchiveBatchItemRepository.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        AttachmentStorage storage = mock(AttachmentStorage.class);
        IndexWriteTargets writeTargets = mock(IndexWriteTargets.class);
        when(writeTargets.current()).thenReturn(List.of());

        ArchiveBatch batch = new ArchiveBatch("batch-42", ArchiveBatch.SCOPE_PAGE, 8L, 5L,
                9L, Instant.parse("2026-09-14T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"), 1, ArchiveBatch.ORIGIN_NORMAL);
        ReflectionTestUtils.setField(batch, "id", 42L);
        ArchiveBatchItem item = new ArchiveBatchItem(42L, ArchiveBatchItem.RESOURCE_ATTACHMENT,
                8L, 5L, ArchiveBatchItem.PRIOR_ACTIVE, null, 1L);
        ReflectionTestUtils.setField(item, "id", 99L);
        when(batches.findById(42L)).thenReturn(Optional.of(batch));
        when(batchItems.findByBatchIdOrderByIdAsc(42L)).thenReturn(List.of(item));
        when(jdbc.queryForObject(any(String.class), eq(Long.class), eq(42L))).thenReturn(42L);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("SELECT COUNT(*) FROM source_document"),
                eq(Integer.class), eq(8L))).thenReturn(0);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("SELECT COUNT(*) FROM page_revision_media"),
                eq(Integer.class), eq(8L))).thenReturn(0);
        when(jdbc.queryForObject(eq("SELECT blob_id FROM attachment WHERE id = ?"),
                eq(Long.class), eq(8L))).thenReturn(17L);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("COALESCE(ab.content_center_file_id"),
                eq(Long.class), eq(8L))).thenReturn(700L);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("attachment.id <> ?"),
                eq(Integer.class), eq(700L), eq(8L))).thenReturn(1);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("COUNT(*) FROM attachment WHERE blob_id = ?"),
                eq(Integer.class), eq(17L))).thenReturn(1);

        RecycleBinCleanupService service = new RecycleBinCleanupService(
                batches, batchItems, StandardTestProperties.nullProvider(), writeTargets,
                new TransactionRunner(null), StandardTestProperties.providerOf(jdbc),
                StandardTestProperties.providerOf(storage), 20,
                Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC));

        service.purgeNow(42L);

        verify(storage, never()).delete(700L);
        verify(jdbc, never()).update("DELETE FROM attachment_blob WHERE id = ?", 17L);
    }

    @Test
    void purgeKnowledgeBaseRecursivelyDeletesResidualPagesAttachmentsAndContentCenterFiles() {
        ArchiveBatchRepository batches = mock(ArchiveBatchRepository.class);
        ArchiveBatchItemRepository batchItems = mock(ArchiveBatchItemRepository.class);
        JdbcOperations jdbc = mock(JdbcOperations.class);
        AttachmentStorage storage = mock(AttachmentStorage.class);
        IndexWriteTargets writeTargets = mock(IndexWriteTargets.class);
        when(writeTargets.current()).thenReturn(List.of());

        ArchiveBatch batch = new ArchiveBatch("batch-42", ArchiveBatch.SCOPE_KNOWLEDGE_BASE,
                5L, 5L, 9L, Instant.parse("2026-09-14T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"), 1, ArchiveBatch.ORIGIN_NORMAL);
        ReflectionTestUtils.setField(batch, "id", 42L);
        ArchiveBatchItem item = new ArchiveBatchItem(42L,
                ArchiveBatchItem.RESOURCE_KNOWLEDGE_BASE, 5L, 5L,
                ArchiveBatchItem.PRIOR_ACTIVE, null, 1L);
        ReflectionTestUtils.setField(item, "id", 99L);
        when(batches.findById(42L)).thenReturn(Optional.of(batch));
        when(batchItems.findByBatchIdOrderByIdAsc(42L)).thenReturn(List.of(item));
        when(jdbc.queryForObject(any(String.class), eq(Long.class), eq(42L))).thenReturn(42L);
        when(jdbc.queryForList(eq("SELECT id FROM wiki_page WHERE kb_id = ? ORDER BY id DESC"),
                eq(Long.class), eq(5L))).thenReturn(List.of(12L, 11L));
        when(jdbc.queryForList(eq("SELECT id FROM attachment WHERE kb_id = ? ORDER BY id DESC"),
                eq(Long.class), eq(5L))).thenReturn(List.of(8L));
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM source_document"),
                eq(Integer.class), eq(8L))).thenReturn(0);
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM page_revision_media"),
                eq(Integer.class), eq(8L))).thenReturn(0);
        when(jdbc.queryForObject(eq("SELECT blob_id FROM attachment WHERE id = ?"),
                eq(Long.class), eq(8L))).thenReturn(17L);
        when(jdbc.queryForObject(contains("COALESCE(ab.content_center_file_id"),
                eq(Long.class), eq(8L))).thenReturn(700L);
        when(jdbc.queryForObject(contains("attachment.id <> ?"),
                eq(Integer.class), eq(700L), eq(8L))).thenReturn(0);
        when(jdbc.queryForObject(contains("COUNT(*) FROM attachment WHERE blob_id = ?"),
                eq(Integer.class), eq(17L))).thenReturn(0);
        when(jdbc.queryForList(contains("SELECT DISTINCT content_id FROM derived_image_asset"),
                eq(Long.class), eq(5L))).thenReturn(List.of(701L));
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM attachment"),
                eq(Integer.class), eq(701L))).thenReturn(0);
        when(jdbc.queryForObject(contains("source_kb_id <> ?"),
                eq(Integer.class), eq(701L), eq(5L))).thenReturn(0);

        RecycleBinCleanupService service = new RecycleBinCleanupService(
                batches, batchItems, StandardTestProperties.nullProvider(), writeTargets,
                new TransactionRunner(null), StandardTestProperties.providerOf(jdbc),
                StandardTestProperties.providerOf(storage), 20,
                Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC));

        service.purgeNow(42L);

        verify(jdbc).update(contains("DELETE FROM resource_join_request"),
                eq("KB"), eq(5L), eq("KB"), eq(5L));
        verify(jdbc).update(
                "DELETE FROM resource_invitation WHERE resource_type = ? AND resource_id = ?",
                "KB", 5L);
        verify(jdbc).update(
                "DELETE FROM ownership_transfer WHERE resource_type = ? AND resource_id = ?",
                "KB", 5L);
        verify(jdbc).update("DELETE FROM wiki_page WHERE id = ?", 12L);
        verify(jdbc).update("DELETE FROM wiki_page WHERE id = ?", 11L);
        verify(jdbc).update("DELETE FROM attachment WHERE id = ?", 8L);
        verify(storage).delete(700L);
        verify(storage).delete(701L);
        verify(jdbc).update("DELETE FROM derived_image_asset WHERE source_kb_id = ?", 5L);
        verify(jdbc).update("DELETE FROM knowledge_base WHERE id = ?", 5L);
    }
}
