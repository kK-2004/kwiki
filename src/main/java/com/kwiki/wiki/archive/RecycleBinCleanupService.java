package com.kwiki.wiki.archive;

import com.kwiki.indexing.search.ChunkIndexRepository;
import com.kwiki.wiki.domain.ArchiveBatch;
import com.kwiki.wiki.domain.ArchiveBatchItem;
import com.kwiki.wiki.persistence.ArchiveBatchItemRepository;
import com.kwiki.wiki.persistence.ArchiveBatchRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Daily physical cleanup of recycle-bin batches whose retention window has
 * fully elapsed. One fixed cutoff per job; only purgeAfter &lt; cutoff rows are
 * eligible (a batch that merely reached its expiry stays until the next run —
 * and is no longer restorable). Items are processed in short transactions with
 * the batch row locked, re-verifying state/expiry so a concurrent restore can
 * never race a deletion to a half-state. Failures isolate per item: failed
 * items simply stay un-purged and are retried by the next run — batches are
 * walked by id, never by an advancing cursor that could skip them.
 *
 * <p>Local data only: content-center files keep their own lifecycle (the SDK
 * exposes no delete); shared attachments, live resources, and conversation
 * snapshots are never touched.</p>
 */
@Service
public class RecycleBinCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RecycleBinCleanupService.class);

    public record CleanupStats(int scannedBatches, int purgedItems, int skippedItems,
                               int failedItems, long durationMs) {}

    private final ArchiveBatchRepository batches;
    private final ArchiveBatchItemRepository batchItems;
    private final ChunkIndexRepository chunkIndex;
    private final ResourceIndexMutex mutex;
    private final TransactionRunner transactions;
    private final JdbcOperations jdbc;
    private final Clock clock;
    private final int batchSize;

    @org.springframework.beans.factory.annotation.Autowired
    public RecycleBinCleanupService(ArchiveBatchRepository batches,
                                    ArchiveBatchItemRepository batchItems,
                                    ObjectProvider<ChunkIndexRepository> chunkIndex,
                                    ResourceIndexMutex mutex,
                                    TransactionRunner transactions,
                                    ObjectProvider<JdbcOperations> jdbc,
                                    @Value("${kwiki.archive.cleanup-batch-size:200}") int batchSize) {
        this(batches, batchItems, chunkIndex, mutex, transactions, jdbc, batchSize,
                Clock.systemUTC());
    }

    public RecycleBinCleanupService(ArchiveBatchRepository batches,
                                    ArchiveBatchItemRepository batchItems,
                                    ObjectProvider<ChunkIndexRepository> chunkIndex,
                                    ResourceIndexMutex mutex,
                                    TransactionRunner transactions,
                                    ObjectProvider<JdbcOperations> jdbc,
                                    int batchSize,
                                    Clock clock) {
        this.batches = batches;
        this.batchItems = batchItems;
        this.chunkIndex = chunkIndex.getIfAvailable();
        this.mutex = mutex;
        this.transactions = transactions;
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
        this.batchSize = batchSize;
        this.clock = clock;
    }

    /** One full pass over expired batches; returns the aggregate counters. */
    public CleanupStats runOnce(String jobId) {
        Instant cutoff = clock.instant();
        long started = System.nanoTime();
        int scanned = 0;
        int purged = 0;
        int skipped = 0;
        int failed = 0;
        log.info("recycle-bin cleanup {} started cutoff={} batchSize={}", jobId, cutoff, batchSize);
        if (jdbc == null) {
            log.info("recycle-bin cleanup {} finished scanned=0 deleted=0 skipped=0 failed=0 durationMs=0 (no jdbc)",
                    jobId);
            return new CleanupStats(0, 0, 0, 0, 0);
        }
        while (true) {
            List<ArchiveBatch> expired = batches.findByStateAndPurgeAfterBeforeOrderByPurgeAfterAsc(
                    ArchiveBatch.STATE_ARCHIVED, cutoff, PageRequest.of(0, 1));
            if (expired.isEmpty()) {
                break;
            }
            ArchiveBatch batch = expired.get(0);
            scanned++;
            ItemOutcome outcome = purgeBatch(batch, cutoff);
            switch (outcome) {
                case PURGED, PARTIAL -> {
                    purged += outcome == ItemOutcome.PURGED ? batch.getItemCount() : 0;
                    if (outcome == ItemOutcome.PARTIAL) {
                        failed++;
                    }
                }
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
                default -> {
                }
            }
            if (outcome == ItemOutcome.PURGED || outcome == ItemOutcome.SKIPPED) {
                continue;
            }
            // PARTIAL/FAILED: stop this pass to avoid a hot loop on the same
            // stuck batch; the next scheduled run retries it.
            break;
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        log.info("recycle-bin cleanup {} finished cutoff={} scanned={} deleted={} skipped={} failed={} durationMs={}",
                jobId, cutoff, scanned, purged, skipped, failed, durationMs);
        return new CleanupStats(scanned, purged, skipped, failed, durationMs);
    }

    private enum ItemOutcome {
        PURGED, PARTIAL, SKIPPED, FAILED
    }

    /**
     * Purges one batch item by item. The whole batch (items + row) disappears
     * only when every item completed; single-item failures keep the batch for
     * the next run.
     */
    private ItemOutcome purgeBatch(ArchiveBatch batch, Instant cutoff) {
        List<ArchiveBatchItem> items = batchItems.findByBatchIdOrderByIdAsc(batch.getId());
        int failures = 0;
        for (ArchiveBatchItem item : items) {
            if (item.isPurged()) {
                continue;
            }
            try {
                if (!purgeItem(batch, item, cutoff)) {
                    failures++;
                }
            } catch (Exception e) {
                failures++;
                log.warn("recycle-bin cleanup item failed batch={} resource={}:{} reason={}",
                        batch.getId(), item.getResourceType(), item.getResourceId(),
                        e.getMessage());
            }
        }
        if (failures > 0) {
            return failures == items.size() ? ItemOutcome.FAILED : ItemOutcome.PARTIAL;
        }
        transactions.inTransaction(() -> {
            if (jdbc == null) {
                return;
            }
            jdbc.update("DELETE FROM archive_batch_item WHERE batch_id = ?", batch.getId());
            jdbc.update("DELETE FROM archive_batch WHERE id = ?", batch.getId());
        });
        log.info("recycle-bin cleanup batch {} fully purged (items={})", batch.getId(),
                items.size());
        return ItemOutcome.PURGED;
    }

    /**
     * One item in one short transaction: lock the batch row, re-verify state
     * and expiry, idempotently re-delete ES chunks, then remove local rows in
     * dependency order and mark the item purged.
     */
    private boolean purgeItem(ArchiveBatch batch, ArchiveBatchItem item, Instant cutoff) {
        return Boolean.TRUE.equals(transactions.inTransactionReturning(() -> {
            if (jdbc == null) {
                return false;
            }
            jdbc.queryForObject("SELECT id FROM archive_batch WHERE id = ? FOR UPDATE",
                    Long.class, batch.getId());
            ArchiveBatch locked = batches.findById(batch.getId()).orElse(null);
            if (locked == null || !ArchiveBatch.STATE_ARCHIVED.equals(locked.getState())) {
                return true; // restored concurrently: nothing to purge
            }
            if (!locked.getPurgeAfter().isBefore(cutoff)) {
                return true; // re-check against the fixed cutoff
            }
            // Idempotent ES re-delete first: never claim local purge while
            // chunks could still be searchable.
            if (chunkIndex != null) {
                if (ArchiveBatchItem.RESOURCE_PAGE.equals(item.getResourceType())) {
                    chunkIndex.deleteResourceChunksChecked("PAGE", item.getResourceId());
                } else if (ArchiveBatchItem.RESOURCE_ATTACHMENT.equals(item.getResourceType())) {
                    chunkIndex.deleteResourceChunksChecked("ATTACHMENT", item.getResourceId());
                }
            }
            switch (item.getResourceType()) {
                case ArchiveBatchItem.RESOURCE_PAGE -> deletePageRows(item.getResourceId());
                case ArchiveBatchItem.RESOURCE_ATTACHMENT -> deleteAttachmentIfUnreferenced(
                        item.getResourceId());
                case ArchiveBatchItem.RESOURCE_KNOWLEDGE_BASE -> deleteKnowledgeBaseRows(
                        item.getResourceId());
                default -> {
                }
            }
            jdbc.update("UPDATE archive_batch_item SET purged = TRUE, purged_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(clock.instant()), item.getId());
            return true;
        }));
    }

    /** FK/logical dependency order (see implementation-notes.md §1.3). */
    private void deletePageRows(long pageId) {
        jdbc.update("DELETE FROM notification WHERE page_id = ?", pageId);
        jdbc.update("""
                DELETE FROM notification WHERE comment_id IN
                    (SELECT id FROM wiki_comment WHERE page_id = ?)
                """, pageId);
        jdbc.update("""
                DELETE FROM comment_mention WHERE comment_id IN
                    (SELECT id FROM wiki_comment WHERE page_id = ?)
                """, pageId);
        jdbc.update("""
                DELETE FROM comment_like WHERE comment_id IN
                    (SELECT id FROM wiki_comment WHERE page_id = ?)
                """, pageId);
        jdbc.update("DELETE FROM wiki_comment WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM selection_anchor WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM page_like WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM page_favorite WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM wiki_page_member WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM wiki_page_audience_member WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM recent_visit WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM page_summary WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM stats_revision WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM stats_repair WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM wiki_link WHERE from_page_id = ? OR to_page_id = ?", pageId, pageId);
        jdbc.update("DELETE FROM wiki_page_tag WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM source_document WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM page_revision_media WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM wiki_page_draft WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM wiki_page_revision WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM indexing_job WHERE resource_type = 'PAGE' AND resource_id = ?",
                pageId);
        jdbc.update("DELETE FROM wiki_page WHERE id = ?", pageId);
    }

    /** Only physically deletes attachments nothing else still references. */
    private void deleteAttachmentIfUnreferenced(long attachmentId) {
        Integer sources = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_document WHERE attachment_id = ?",
                Integer.class, attachmentId);
        Integer mediaRefs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM page_revision_media WHERE attachment_id = ?",
                Integer.class, attachmentId);
        if ((sources != null && sources > 0) || (mediaRefs != null && mediaRefs > 0)) {
            return; // shared: keep file/metadata, only the index is gone
        }
        jdbc.update("DELETE FROM indexing_job WHERE resource_type = 'ATTACHMENT' AND resource_id = ?",
                attachmentId);
        jdbc.update("DELETE FROM attachment WHERE id = ?", attachmentId);
    }

    private void deleteKnowledgeBaseRows(long kbId) {
        // Pages/attachments of this kb were their own batch items; this only
        // removes kb-level relationships and the row itself.
        jdbc.update("DELETE FROM resource_invitation WHERE kb_id = ?", kbId);
        jdbc.update("DELETE FROM resource_join_request WHERE kb_id = ?", kbId);
        jdbc.update("DELETE FROM ownership_transfer WHERE kb_id = ?", kbId);
        jdbc.update("DELETE FROM wiki_import_job WHERE kb_id = ?", kbId);
        jdbc.update("DELETE FROM knowledge_base_member WHERE kb_id = ?", kbId);
        jdbc.update("DELETE FROM archive_batch_item WHERE resource_type = 'KNOWLEDGE_BASE' AND resource_id = ?",
                kbId);
        jdbc.update("DELETE FROM indexing_job WHERE resource_type = 'KNOWLEDGE_BASE' AND resource_id = ?",
                kbId);
        jdbc.update("DELETE FROM scope_version WHERE kb_id = ?", kbId);
        jdbc.update("DELETE FROM knowledge_base WHERE id = ?", kbId);
    }

    public String newJobId() {
        return "recycle-bin-" + UUID.randomUUID();
    }
}
