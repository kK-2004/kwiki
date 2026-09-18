package com.kwiki.wiki.archive;

import com.kwiki.indexing.search.ChunkIndexRepository;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.domain.ArchiveBatch;
import com.kwiki.wiki.persistence.ArchiveBatchItemRepository;
import com.kwiki.wiki.persistence.ArchiveBatchRepository;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 归档与索引竞态的互斥语义：锁繁忙/锁服务不可用时批次保持 PENDING
 * 且绝不触碰 ES；持锁路径完成删除后才标记 SYNCED。全部使用 mock，
 * 无数据库、无 ES、无 Redis。
 */
class ArchiveIndexSyncLockTest {

    private ChunkIndexRepository chunkIndex;
    private ArchiveBatchRepository batches;
    private ArchiveBatchItemRepository batchItems;
    private TransactionRunner transactions;
    private com.kk2004.common.lock.DistributedLock lock;
    private com.kk2004.common.lock.DistributedLockFactory factory;
    private ArchiveIndexSyncService service;

    @BeforeEach
    void setUp() {
        chunkIndex = mock(ChunkIndexRepository.class);
        batches = mock(ArchiveBatchRepository.class);
        batchItems = mock(ArchiveBatchItemRepository.class);
        transactions = mock(TransactionRunner.class);
        lock = mock(com.kk2004.common.lock.DistributedLock.class);
        factory = mock(com.kk2004.common.lock.DistributedLockFactory.class);
        when(factory.getDistributedLock(anyString())).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(transactions).inTransaction(any(Runnable.class));
        KwikiDistributedLocks locks = new KwikiDistributedLocks(
                StandardTestProperties.providerOf(factory));
        com.kwiki.indexing.version.SearchIndexVersionRepository versionRepository =
                mock(com.kwiki.indexing.version.SearchIndexVersionRepository.class);
        com.kwiki.indexing.version.SearchIndexVersion v1 =
                com.kwiki.indexing.version.SearchIndexVersion.bootstrapped(
                        1, "kwiki-chunks-v1",
                        new com.kwiki.indexing.version.EditableIndexConfig(
                                "kwiki-parse-1", "kwiki-chunk-1", "default",
                                "text-embedding-v4", 1024, 1),
                        "hash");
        when(versionRepository.findByWriteEnabledTrueAndDeletedAtIsNull())
                .thenReturn(List.of(v1));
        com.kwiki.indexing.version.IndexWriteTargets writeTargets =
                new com.kwiki.indexing.version.IndexWriteTargets(
                        StandardTestProperties.providerOf(versionRepository));
        service = new ArchiveIndexSyncService(
                StandardTestProperties.providerOf(chunkIndex), batches, batchItems, writeTargets,
                locks, transactions);
    }

    private ArchiveBatch kbScopedBatch() {
        ArchiveBatch batch = mock(ArchiveBatch.class);
        when(batch.getId()).thenReturn(11L);
        when(batch.getKbId()).thenReturn(3L);
        when(batch.getIndexSyncStatus()).thenReturn(ArchiveBatch.SYNC_PENDING);
        when(batch.getScopeType()).thenReturn(ArchiveBatch.SCOPE_KNOWLEDGE_BASE);
        return batch;
    }

    @Test
    void busyMutexDefersTheDeletionWithoutTouchingElasticsearch() throws Exception {
        when(lock.tryLock(anyLong(), any(java.util.concurrent.TimeUnit.class))).thenReturn(false);
        ArchiveBatch batch = kbScopedBatch();

        String status = service.attemptSync(batch);

        assertThat(status).isEqualTo(ArchiveBatch.SYNC_PENDING);
        verify(chunkIndex, never()).deleteKnowledgeBaseChunksChecked(anyString(), anyLong());
        verify(lock, never()).unlock();
    }

    @Test
    void unavailableLockServiceDefersTheDeletion() {
        KwikiDistributedLocks unavailable = new KwikiDistributedLocks(
                StandardTestProperties.nullProvider());
        ArchiveIndexSyncService offline = new ArchiveIndexSyncService(
                StandardTestProperties.providerOf(chunkIndex), batches, batchItems,
                new com.kwiki.indexing.version.IndexWriteTargets(
                        StandardTestProperties.nullProvider()),
                unavailable, transactions);
        ArchiveBatch batch = kbScopedBatch();

        assertThat(offline.attemptSync(batch)).isEqualTo(ArchiveBatch.SYNC_PENDING);
        verify(chunkIndex, never()).deleteKnowledgeBaseChunksChecked(anyString(), anyLong());
    }

    @Test
    void cleanDeletionUnderLockMarksTheBatchSynced() throws Exception {
        when(lock.tryLock(anyLong(), any(java.util.concurrent.TimeUnit.class))).thenReturn(true);
        when(chunkIndex.deleteKnowledgeBaseChunksChecked("kwiki-chunks-v1", 3L))
                .thenReturn(new ChunkIndexRepository.DeleteOutcome(5, false, 0));
        ArchiveBatch live = kbScopedBatch();
        when(live.isArchivedState()).thenReturn(true);
        when(batches.findById(11L)).thenReturn(java.util.Optional.of(live));
        ArchiveBatch batch = kbScopedBatch();

        String status = service.attemptSync(batch);

        assertThat(status).isEqualTo(ArchiveBatch.SYNC_SYNCED);
        verify(live).markIndexSynced();
        verify(batches).save(live);
        verify(lock).unlock();
    }

    @Test
    void dirtyDeletionStaysPendingForTheRetryTask() throws Exception {
        when(lock.tryLock(anyLong(), any(java.util.concurrent.TimeUnit.class))).thenReturn(true);
        when(chunkIndex.deleteKnowledgeBaseChunksChecked("kwiki-chunks-v1", 3L))
                .thenReturn(new ChunkIndexRepository.DeleteOutcome(2, true, 1));
        ArchiveBatch batch = kbScopedBatch();

        assertThat(service.attemptSync(batch)).isEqualTo(ArchiveBatch.SYNC_PENDING);
        verify(batches, never()).save(any(ArchiveBatch.class));
        verify(lock).unlock();
    }

    @Test
    void elasticsearchFailureStaysPendingAndStillReleasesTheLock() throws Exception {
        when(lock.tryLock(anyLong(), any(java.util.concurrent.TimeUnit.class))).thenReturn(true);
        when(chunkIndex.deleteKnowledgeBaseChunksChecked("kwiki-chunks-v1", 3L))
                .thenThrow(new IllegalStateException("es unavailable"));
        ArchiveBatch batch = kbScopedBatch();

        assertThat(service.attemptSync(batch)).isEqualTo(ArchiveBatch.SYNC_PENDING);
        verify(lock).unlock();
    }

    @Test
    void mutexIsScopedPerKnowledgeBase() throws Exception {
        when(lock.tryLock(anyLong(), any(java.util.concurrent.TimeUnit.class))).thenReturn(false);
        ArchiveBatch batch = kbScopedBatch();

        service.attemptSync(batch);

        verify(factory).getDistributedLock("kwiki:lock:index-kb:3");
    }
}
