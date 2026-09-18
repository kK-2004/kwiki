package com.kwiki.wiki.archive;

import com.kwiki.indexing.search.ChunkIndexRepository;
import com.kwiki.indexing.version.IndexWriteTargets;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
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

import java.time.Duration;
import java.util.List;

/**
 * 针对刚提交的归档批次的同步 ES 删除，外加对首次尝试后仍为 PENDING 批次的
 * 有界重试任务。删除在每一个当前 writeEnabled 的物理索引上执行
 * （读别名不接受写）；按知识库维度的 SDK 索引互斥锁
 * （kwiki:lock:index-kb:{kbId}）将此操作与 worker 的 upsert 串行化，
 * 避免竞态写入夹在提交与删除之间；事务性发件箱（indexing_job 行）在
 * ES 不可用时仍作为兜底路径——权威的生命周期过滤器确保内容无论
 * ES 状态如何都不可检索。
 */
@Service
@EnableScheduling
public class ArchiveIndexSyncService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveIndexSyncService.class);

    private static final String KB_MUTEX_PURPOSE = "index-kb:";

    private final ChunkIndexRepository chunkIndex;
    private final ArchiveBatchRepository batches;
    private final ArchiveBatchItemRepository batchItems;
    private final IndexWriteTargets writeTargets;
    private final KwikiDistributedLocks locks;
    private final TransactionRunner transactions;

    public ArchiveIndexSyncService(ObjectProvider<ChunkIndexRepository> chunkIndex,
                                   ArchiveBatchRepository batches,
                                   ArchiveBatchItemRepository batchItems,
                                   IndexWriteTargets writeTargets,
                                   KwikiDistributedLocks locks,
                                   TransactionRunner transactions) {
        this.chunkIndex = chunkIndex.getIfAvailable();
        this.batches = batches;
        this.batchItems = batchItems;
        this.writeTargets = writeTargets;
        this.locks = locks;
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
        AutoCloseable lock = locks.acquire(KB_MUTEX_PURPOSE + batch.getKbId(), Duration.ofSeconds(10));
        if (lock == null) {
            // 互斥锁繁忙或锁服务不可用：归档仍为权威，稍后重试。
            log.info("archive batch {} index deletion deferred: kb mutex busy (kbId={})",
                    batch.getId(), batch.getKbId());
            return ArchiveBatch.SYNC_PENDING;
        }
        try (AutoCloseable held = lock) {
            boolean clean = true;
            if (ArchiveBatch.SCOPE_KNOWLEDGE_BASE.equals(batch.getScopeType())) {
                for (var target : writeTargets.current()) {
                    var outcome = chunkIndex.deleteKnowledgeBaseChunksChecked(
                            target.physicalName(), batch.getKbId());
                    clean &= outcome.clean();
                }
            } else {
                for (ArchiveBatchItem item : batchItems.findByBatchIdOrderByIdAsc(batch.getId())) {
                    if (ArchiveBatchItem.RESOURCE_PAGE.equals(item.getResourceType())) {
                        for (var target : writeTargets.current()) {
                            var outcome = chunkIndex.deleteResourceChunksChecked(
                                    target.physicalName(), "PAGE", item.getResourceId());
                            clean &= outcome.clean();
                        }
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
        } catch (Exception e) {
            // ES 不可用：归档仍为权威，稍后重试。
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
