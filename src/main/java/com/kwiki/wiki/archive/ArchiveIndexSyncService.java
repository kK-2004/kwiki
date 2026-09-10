package com.kwiki.wiki.archive;

import com.kwiki.indexing.search.ChunkIndexRepository;
import com.kwiki.wiki.domain.ArchiveBatch;
import com.kwiki.wiki.domain.ArchiveBatchItem;
import com.kwiki.wiki.persistence.ArchiveBatchItemRepository;
import com.kwiki.wiki.persistence.ArchiveBatchRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 针对刚提交的归档批次的同步 ES 删除，外加对首次尝试后仍为 PENDING 批次的
 * 有界重试任务。按知识库维度的索引互斥锁将此操作与 worker 的 upsert 串行化，
 * 避免竞态写入夹在提交与删除之间；事务性发件箱（indexing_job 行）在 ES 不可用
 * 时仍作为兜底路径——权威的生命周期过滤器确保内容无论 ES 状态如何都
 * 不可检索。
 */
@Service
@EnableScheduling
public class ArchiveIndexSyncService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveIndexSyncService.class);

    private final ChunkIndexRepository chunkIndex;
    private final ArchiveBatchRepository batches;
    private final ArchiveBatchItemRepository batchItems;
    private final ResourceIndexMutex mutex;
    private final TransactionRunner transactions;

    public ArchiveIndexSyncService(ObjectProvider<ChunkIndexRepository> chunkIndex,
                                   ArchiveBatchRepository batches,
                                   ArchiveBatchItemRepository batchItems,
                                   ResourceIndexMutex mutex,
                                   TransactionRunner transactions) {
        this.chunkIndex = chunkIndex.getIfAvailable();
        this.batches = batches;
        this.batchItems = batchItems;
        this.mutex = mutex;
        this.transactions = transactions;
    }

    /**
     * 尝试对某个已提交批次执行同步删除。返回该批次的 indexSyncStatus：
     * 仅当所有删除均完成且无超时/冲突、且刷新使其可见时才为 SYNCED；
     * 任何失败都使其保持 PENDING，交由重试任务与发件箱处理。
     */
    public String attemptSync(ArchiveBatch batch) {
        if (chunkIndex == null || ArchiveBatch.SYNC_SYNCED.equals(batch.getIndexSyncStatus())) {
            return batch.getIndexSyncStatus();
        }
        try (AutoCloseable lock = mutex.acquireKb(batch.getKbId(), 10)) {
            boolean clean = true;
            if (ArchiveBatch.SCOPE_KNOWLEDGE_BASE.equals(batch.getScopeType())) {
                var outcome = chunkIndex.deleteKnowledgeBaseChunksChecked(batch.getKbId());
                clean = outcome.clean();
            } else {
                for (ArchiveBatchItem item : batchItems.findByBatchIdOrderByIdAsc(batch.getId())) {
                    if (ArchiveBatchItem.RESOURCE_PAGE.equals(item.getResourceType())) {
                        var outcome = chunkIndex.deleteResourceChunksChecked(
                                "PAGE", item.getResourceId());
                        clean &= outcome.clean();
                    }
                }
            }
            if (clean) {
                transactions.inTransaction(() -> batches
                        .findById(batch.getId())
                        .filter(ArchiveBatch::isArchivedState)
                        .ifPresent(live -> {
                            live.markIndexSynced();
                            batches.save(live);
                        }));
                log.info("archive batch {} index deletion synced (kbId={}, items={})",
                        batch.getId(), batch.getKbId(), batch.getItemCount());
                return ArchiveBatch.SYNC_SYNCED;
            }
            log.warn("archive batch {} index deletion incomplete, staying PENDING (kbId={})",
                    batch.getId(), batch.getKbId());
            return ArchiveBatch.SYNC_PENDING;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ArchiveBatch.SYNC_PENDING;
        } catch (Exception e) {
            // ES 不可用或互斥锁繁忙：归档仍为权威，稍后重试。
            log.warn("archive batch {} synchronous index deletion failed (kbId={}): {}",
                    batch.getId(), batch.getKbId(), rootCauseMessage(e));
            log.debug("archive batch {} index deletion failure details", batch.getId(), e);
            return ArchiveBatch.SYNC_PENDING;
        }
    }

    private static String rootCauseMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    /** 对 PENDING 批次的有界重试，按最旧优先。 */
    @Scheduled(fixedDelayString = "${kwiki.archive.index-retry-interval:30s}",
               initialDelayString = "${kwiki.archive.index-retry-initial-delay:20s}")
    public void retryPendingBatches() {
        List<ArchiveBatch> pending = batches.findByStateAndIndexSyncStatusOrderByArchivedAtAsc(
                ArchiveBatch.STATE_ARCHIVED, ArchiveBatch.SYNC_PENDING, PageRequest.of(0, 50));
        for (ArchiveBatch batch : pending) {
            attemptSync(batch);
        }
    }
}
