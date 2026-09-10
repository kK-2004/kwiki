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
 * Synchronous ES deletion for freshly committed archive batches, plus the
 * bounded retry task for batches whose first attempt left them PENDING. The
 * per-knowledge-base index mutex serializes this against worker upserts so a
 * racing write cannot slip between the commit and the delete; the transactional
 * outbox (indexing_job rows) remains the belt-and-suspenders path when ES was
 * unavailable — the authoritative lifecycle filter keeps the content
 * unretrievable regardless of ES state.
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
     * Attempts the synchronous deletion for a committed batch. Returns the
     * resulting batch indexSyncStatus: SYNCED only when every delete finished
     * without timeouts/conflicts and the refresh made it visible; any failure
     * keeps PENDING for the retry task and the outbox.
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
            // ES unavailable or mutex busy: archive stays authoritative, retry later.
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

    /** Bounded retry of PENDING batches, oldest first. */
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
