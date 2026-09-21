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
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("COALESCE(blob.content_center_file_id"),
                eq(Long.class), eq(8L))).thenReturn(700L);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("content_center_file_id = ? AND id <> ?"),
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
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("COALESCE(blob.content_center_file_id"),
                eq(Long.class), eq(8L))).thenReturn(700L);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("content_center_file_id = ? AND id <> ?"),
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
}
