package com.kwiki.wiki.archive;

import com.kwiki.indexing.search.ChunkIndexRepository;
import com.kwiki.wiki.attach.AttachmentStorage;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对保留窗口已完全届满的回收站批次执行的每日物理清理。每次任务使用固定的
 * 一个截止点；只有 purgeAfter &lt; cutoff 的行符合条件（仅到达过期的批次会
 * 保留到下一次运行——且不再可恢复）。条目在锁定批次行的短事务中处理，
 * 并重新校验状态/过期时间，使并发恢复绝不会与删除竞态进入半成品状态。
 * 失败按条目隔离：失败的条目保持未清除，由下一次运行重试——批次按 id 遍历，
 * 绝不经由可能跳过的滚动游标。
 *
 * <p>附件只会在没有任何页面、修订或其他附件引用时才被清除；对应的
 * 内容中心文件会在同一条清理路径中删除。实时资源与对话快照均不会被触及。</p>
 */
@Service
public class RecycleBinCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RecycleBinCleanupService.class);

    public record CleanupStats(int scannedBatches, int purgedItems, int skippedItems,
                               int failedItems, long durationMs) {}

    /** 用户从回收站主动发起的永久删除结果。 */
    public record PurgeResult(long batchId, int purgedItems) {}

    private final ArchiveBatchRepository batches;
    private final ArchiveBatchItemRepository batchItems;
    private final ChunkIndexRepository chunkIndex;
    private final com.kwiki.indexing.version.IndexWriteTargets writeTargets;
    private final TransactionRunner transactions;
    private final JdbcOperations jdbc;
    private final AttachmentStorage attachmentStorage;
    private final Clock clock;
    private final int batchSize;

    @org.springframework.beans.factory.annotation.Autowired
    public RecycleBinCleanupService(ArchiveBatchRepository batches,
                                    ArchiveBatchItemRepository batchItems,
                                    ObjectProvider<ChunkIndexRepository> chunkIndex,
                                    com.kwiki.indexing.version.IndexWriteTargets writeTargets,
                                    TransactionRunner transactions,
                                    ObjectProvider<JdbcOperations> jdbc,
                                    ObjectProvider<AttachmentStorage> attachmentStorage,
                                    @Value("${kwiki.archive.cleanup-batch-size:200}") int batchSize) {
        this(batches, batchItems, chunkIndex, writeTargets, transactions, jdbc, attachmentStorage, batchSize,
                Clock.systemUTC());
    }

    public RecycleBinCleanupService(ArchiveBatchRepository batches,
                                    ArchiveBatchItemRepository batchItems,
                                    ObjectProvider<ChunkIndexRepository> chunkIndex,
                                    com.kwiki.indexing.version.IndexWriteTargets writeTargets,
                                    TransactionRunner transactions,
                                    ObjectProvider<JdbcOperations> jdbc,
                                    int batchSize,
                                    Clock clock) {
        this(batches, batchItems, chunkIndex, writeTargets, transactions, jdbc, null, batchSize, clock);
    }

    public RecycleBinCleanupService(ArchiveBatchRepository batches,
                                    ArchiveBatchItemRepository batchItems,
                                    ObjectProvider<ChunkIndexRepository> chunkIndex,
                                    com.kwiki.indexing.version.IndexWriteTargets writeTargets,
                                    TransactionRunner transactions,
                                    ObjectProvider<JdbcOperations> jdbc,
                                    ObjectProvider<AttachmentStorage> attachmentStorage,
                                    int batchSize,
                                    Clock clock) {
        this.batches = batches;
        this.batchItems = batchItems;
        this.chunkIndex = chunkIndex.getIfAvailable();
        this.writeTargets = writeTargets;
        this.transactions = transactions;
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
        this.attachmentStorage = attachmentStorage == null ? null : attachmentStorage.getIfAvailable();
        this.batchSize = batchSize;
        this.clock = clock;
    }

    /** 对过期批次的一次完整遍历；返回聚合计数器。 */
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
            // PARTIAL/FAILED：停止本次遍历，避免对同一卡住的批次空转；
            // 下一次计划运行会重试它。
            break;
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        log.info("recycle-bin cleanup {} finished cutoff={} scanned={} deleted={} skipped={} failed={} durationMs={}",
                jobId, cutoff, scanned, purged, skipped, failed, durationMs);
        return new CleanupStats(scanned, purged, skipped, failed, durationMs);
    }

    /**
     * 立即永久删除一个仍在回收站中的批次，不受 7 天保留窗口限制。
     *
     * <p>调用方须先完成权限校验；这里再次锁定并校验批次状态，确保主动删除
     * 与恢复操作不会并发地删除已恢复资源。所有条目在同一个事务中处理，任何
     * 索引或数据库错误都会回滚本地删除，保留批次供用户重试。</p>
     */
    public PurgeResult purgeNow(long batchId) {
        if (jdbc == null) {
            throw new IllegalStateException("recycle-bin purge requires jdbc");
        }
        return transactions.inTransactionReturning(() -> {
            if (batches.findById(batchId).isEmpty()) {
                throw new com.kk2004.common.exception.NotFoundException(
                        "archive batch not found");
            }
            lockBatch(batchId);
            ArchiveBatch batch = batches.findById(batchId)
                    .orElseThrow(() -> new com.kk2004.common.exception.NotFoundException(
                            "archive batch not found"));
            if (ArchiveBatch.STATE_PURGED.equals(batch.getState())) {
                throw new ResourceArchiveService.ExpiredBatchException(
                        "回收站批次已被清理，无法删除");
            }
            if (!ArchiveBatch.STATE_ARCHIVED.equals(batch.getState())) {
                throw new ResourceArchiveService.BatchConflictException(
                        "该批次已恢复，无法永久删除");
            }
            List<ArchiveBatchItem> items = purgeOrder(
                    batchItems.findByBatchIdOrderByIdAsc(batchId));
            int purged = 0;
            Instant cutoff = clock.instant();
            for (ArchiveBatchItem item : items) {
                if (item.isPurged()) {
                    continue;
                }
                purgeItemLocked(batch, item, cutoff, true);
                purged++;
            }
            // 批次行在本事务开始时已加锁，因此恢复无法插入到删除窗口中。
            jdbc.update("DELETE FROM archive_batch_item WHERE batch_id = ?", batchId);
            jdbc.update("DELETE FROM archive_batch WHERE id = ?", batchId);
            log.info("recycle-bin batch {} permanently purged by user (items={})",
                    batchId, purged);
            return new PurgeResult(batchId, purged);
        });
    }

    private enum ItemOutcome {
        PURGED, PARTIAL, SKIPPED, FAILED
    }

    /**
     * 逐条目清除一个批次。整个批次（条目 + 行）只有在每个条目都完成后才会消失；
     * 单个条目的失败会使该批次保留到下一次运行。
     */
    private ItemOutcome purgeBatch(ArchiveBatch batch, Instant cutoff) {
        List<ArchiveBatchItem> items = purgeOrder(
                batchItems.findByBatchIdOrderByIdAsc(batch.getId()));
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
        if (!deleteBatchIfComplete(batch.getId(), cutoff, false)) {
            return ItemOutcome.PARTIAL;
        }
        log.info("recycle-bin cleanup batch {} fully purged (items={})", batch.getId(),
                items.size());
        return ItemOutcome.PURGED;
    }

    /**
     * 在一条短事务中处理一个条目：锁定批次行，重新校验状态与过期时间，
     * 幂等地重新删除 ES 分块，随后按依赖顺序删除本地行并标记该条目已清除。
     */
    private boolean purgeItem(ArchiveBatch batch, ArchiveBatchItem item, Instant cutoff) {
        return Boolean.TRUE.equals(transactions.inTransactionReturning(() -> {
            if (jdbc == null) {
                return false;
            }
            lockBatch(batch.getId());
            ArchiveBatch locked = batches.findById(batch.getId()).orElse(null);
            return purgeItemLocked(locked, item, cutoff, false);
        }));
    }

    private boolean purgeItemLocked(ArchiveBatch locked, ArchiveBatchItem item,
                                    Instant cutoff, boolean force) {
        if (locked == null || !ArchiveBatch.STATE_ARCHIVED.equals(locked.getState())) {
            return true; // 并发已恢复：无可清除内容
        }
        if (!force && !locked.getPurgeAfter().isBefore(cutoff)) {
            return true; // 针对固定截止点重新校验
        }
        // 先幂等地重复删除 ES（每个 writeEnabled 物理索引）：
        // 在分块仍可能被搜索到之前，绝不声称已完成本地清除。
        if (chunkIndex != null) {
            for (var target : writeTargets.current()) {
                if (ArchiveBatchItem.RESOURCE_PAGE.equals(item.getResourceType())) {
                    chunkIndex.deleteResourceChunksChecked(
                            target.physicalName(), "PAGE", item.getResourceId());
                } else if (ArchiveBatchItem.RESOURCE_ATTACHMENT.equals(item.getResourceType())) {
                    chunkIndex.deleteResourceChunksChecked(
                            target.physicalName(), "ATTACHMENT", item.getResourceId());
                } else if (ArchiveBatchItem.RESOURCE_KNOWLEDGE_BASE.equals(item.getResourceType())) {
                    chunkIndex.deleteResourceChunksChecked(
                            target.physicalName(), "KNOWLEDGE_BASE", item.getResourceId());
                }
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
    }

    private void lockBatch(long batchId) {
        jdbc.queryForObject("SELECT id FROM archive_batch WHERE id = ? FOR UPDATE",
                Long.class, batchId);
    }

    private boolean deleteBatchIfComplete(long batchId, Instant cutoff, boolean force) {
        return Boolean.TRUE.equals(transactions.inTransactionReturning(() -> {
            if (jdbc == null) return false;
            if (batches.findById(batchId).isEmpty()) return true;
            lockBatch(batchId);
            ArchiveBatch current = batches.findById(batchId).orElse(null);
            if (current == null || !ArchiveBatch.STATE_ARCHIVED.equals(current.getState())) {
                return true;
            }
            if (!force && !current.getPurgeAfter().isBefore(cutoff)) {
                return false;
            }
            if (batchItems.countByBatchIdAndPurgedFalse(batchId) > 0) {
                return false;
            }
            jdbc.update("DELETE FROM archive_batch_item WHERE batch_id = ?", batchId);
            jdbc.update("DELETE FROM archive_batch WHERE id = ?", batchId);
            return true;
        }));
    }

    /**
     * 按外键依赖顺序清除：页面（子页面优先）→附件→知识库。
     * 归档批次的插入顺序是为了恢复方便，并不等于物理删除顺序。
     */
    private List<ArchiveBatchItem> purgeOrder(List<ArchiveBatchItem> items) {
        Map<Long, ArchiveBatchItem> pages = new HashMap<>();
        for (ArchiveBatchItem item : items) {
            if (ArchiveBatchItem.RESOURCE_PAGE.equals(item.getResourceType())) {
                pages.put(item.getResourceId(), item);
            }
        }
        return items.stream()
                .sorted(Comparator
                        .comparingInt((ArchiveBatchItem item) -> resourcePriority(item))
                        .thenComparingInt(item -> ArchiveBatchItem.RESOURCE_PAGE.equals(item.getResourceType())
                                ? -pageDepth(item, pages) : 0)
                        .thenComparingLong(item -> -item.getResourceId()))
                .toList();
    }

    private int resourcePriority(ArchiveBatchItem item) {
        if (ArchiveBatchItem.RESOURCE_PAGE.equals(item.getResourceType())) return 0;
        if (ArchiveBatchItem.RESOURCE_ATTACHMENT.equals(item.getResourceType())) return 1;
        if (ArchiveBatchItem.RESOURCE_KNOWLEDGE_BASE.equals(item.getResourceType())) return 2;
        return 3;
    }

    private int pageDepth(ArchiveBatchItem item, Map<Long, ArchiveBatchItem> pages) {
        int depth = 0;
        Long parent = item.getPriorParentId();
        int guard = pages.size() + 1;
        while (parent != null && guard-- > 0) {
            ArchiveBatchItem parentItem = pages.get(parent);
            if (parentItem == null) break;
            depth++;
            parent = parentItem.getPriorParentId();
        }
        return depth;
    }

    /** 外键/逻辑依赖顺序（见 implementation-notes.md §1.3）。 */
    private void deletePageRows(long pageId) {
        deleteResourceAccessRows("PAGE", pageId);
        // 先解除尚未归档的外部引用，避免自引用外键阻止物理删除。
        jdbc.update("UPDATE wiki_page SET parent_id = NULL WHERE parent_id = ? AND status = 'ARCHIVED'",
                pageId);
        jdbc.update("DELETE FROM wiki_import_job WHERE page_id = ? OR parent_id = ?",
                pageId, pageId);
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
        jdbc.update("""
                UPDATE wiki_comment
                SET parent_id = NULL, reply_to = NULL, anchor_id = NULL
                WHERE page_id = ?
                """, pageId);
        jdbc.update("DELETE FROM wiki_comment WHERE page_id = ?", pageId);
        jdbc.update("""
                DELETE FROM notification
                WHERE anchor_id IN (SELECT id FROM selection_anchor WHERE page_id = ?)
                """, pageId);
        jdbc.update("""
                UPDATE wiki_comment
                SET anchor_id = NULL
                WHERE anchor_id IN (SELECT id FROM selection_anchor WHERE page_id = ?)
                """, pageId);
        jdbc.update("DELETE FROM selection_anchor WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM page_like WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM page_favorite WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM wiki_page_member WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM wiki_page_audience_member WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM recent_visit WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM page_summary WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM stats_revision WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM stats_repair WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM wiki_link WHERE source_page_id = ? OR target_page_id = ?",
                pageId, pageId);
        jdbc.update("DELETE FROM wiki_page_tag WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM source_document WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM page_revision_media WHERE page_id = ?", pageId);
        jdbc.update("DELETE FROM wiki_page_draft WHERE page_id = ?", pageId);
        jdbc.update("""
                UPDATE wiki_page
                SET current_draft_revision_id = NULL, current_published_revision_id = NULL
                WHERE id = ?
                """, pageId);
        jdbc.update("DELETE FROM wiki_page_revision WHERE page_id = ?", pageId);
        deleteIndexingJobs("PAGE", pageId);
        jdbc.update("DELETE FROM wiki_page WHERE id = ?", pageId);
    }

    /** 只物理删除没有任何其他对象仍引用的附件及其内容中心文件。 */
    private void deleteAttachmentIfUnreferenced(long attachmentId) {
        Integer sources = jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_document WHERE attachment_id = ?",
                Integer.class, attachmentId);
        Integer mediaRefs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM page_revision_media WHERE attachment_id = ?",
                Integer.class, attachmentId);
        if ((sources != null && sources > 0) || (mediaRefs != null && mediaRefs > 0)) {
            return; // 共享：保留文件/元数据，只有索引被移除
        }
        Long blobId = jdbc.queryForObject("SELECT blob_id FROM attachment WHERE id = ?",
                Long.class, attachmentId);
        Long fileId = jdbc.queryForObject("""
                SELECT COALESCE(ab.content_center_file_id, attachment.content_center_file_id)
                FROM attachment
                LEFT JOIN attachment_blob ab ON ab.id = attachment.blob_id
                WHERE attachment.id = ?
                """, Long.class, attachmentId);
        if (fileId != null && fileId > 0) {
            // 锁住同一个物理文件的全部元数据行，避免两个并发清理都看到“还有
            // 一个引用”而同时跳过远端删除，留下内容中心孤儿文件。
            jdbc.queryForList("""
                    SELECT attachment.id FROM attachment
                    LEFT JOIN attachment_blob ab ON ab.id = attachment.blob_id
                    WHERE COALESCE(ab.content_center_file_id,
                                   attachment.content_center_file_id) = ?
                    FOR UPDATE
                    """, Long.class, fileId);
            Integer otherReferences = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM attachment
                    LEFT JOIN attachment_blob ab ON ab.id = attachment.blob_id
                    WHERE COALESCE(ab.content_center_file_id,
                                   attachment.content_center_file_id) = ?
                      AND attachment.id <> ?
                    """, Integer.class, fileId, attachmentId);
            if (otherReferences == null || otherReferences == 0) {
                if (attachmentStorage == null) {
                    throw new IllegalStateException("content-center attachment storage is unavailable");
                }
                attachmentStorage.delete(fileId);
            }
        }
        jdbc.update("DELETE FROM wiki_import_job WHERE source_attachment_id = ?", attachmentId);
        deleteIndexingJobs("ATTACHMENT", attachmentId);
        jdbc.update("DELETE FROM attachment WHERE id = ?", attachmentId);
        if (blobId != null) {
            Integer remainingBlobReferences = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attachment WHERE blob_id = ?", Integer.class, blobId);
            if (remainingBlobReferences == null || remainingBlobReferences == 0) {
                jdbc.update("DELETE FROM attachment_blob WHERE id = ?", blobId);
            }
        }
    }

    private void deleteKnowledgeBaseRows(long kbId) {
        // 正常归档会为当时 ACTIVE/STORED 的页面和附件建立独立批次项。
        // 这里仍以数据库当前内容为准递归兜底，覆盖更早单独归档的页面、
        // ARCHIVED/PENDING 附件，以及旧数据缺少批次项的情况。
        jdbc.update("DELETE FROM wiki_import_job WHERE kb_id = ?", kbId);
        jdbc.update("UPDATE wiki_page SET parent_id = NULL WHERE kb_id = ?", kbId);
        for (Long pageId : jdbc.queryForList(
                "SELECT id FROM wiki_page WHERE kb_id = ? ORDER BY id DESC",
                Long.class, kbId)) {
            deletePageRows(pageId);
        }
        for (Long attachmentId : jdbc.queryForList(
                "SELECT id FROM attachment WHERE kb_id = ? ORDER BY id DESC",
                Long.class, kbId)) {
            deleteAttachmentIfUnreferenced(attachmentId);
        }

        deleteDerivedImageRows(kbId);
        deleteResourceAccessRows("KB", kbId);
        jdbc.update("DELETE FROM knowledge_base_member WHERE kb_id = ?", kbId);
        jdbc.update("DELETE FROM wiki_page_audience_member WHERE source_kb_id = ?", kbId);
        jdbc.update("""
                DELETE FROM wiki_page_tag
                WHERE tag_id IN (SELECT id FROM wiki_tag WHERE kb_id = ?)
                """, kbId);
        jdbc.update("DELETE FROM wiki_tag WHERE kb_id = ?", kbId);
        jdbc.update("DELETE FROM archive_batch_item WHERE resource_type = 'KNOWLEDGE_BASE' AND resource_id = ?",
                kbId);
        jdbc.update("DELETE FROM archive_batch_item WHERE kb_id = ?", kbId);
        jdbc.update("DELETE FROM archive_batch WHERE kb_id = ?", kbId);
        deleteIndexingJobs("KNOWLEDGE_BASE", kbId);
        jdbc.update("DELETE FROM scope_version WHERE kb_id = ?", kbId);
        jdbc.update("DELETE FROM knowledge_base WHERE id = ?", kbId);
    }

    /** 删除通用资源授权表中的行；这些表使用 resource_type/resource_id，而非 kb_id。 */
    private void deleteResourceAccessRows(String resourceType, long resourceId) {
        // join request 对 invitation 有外键，因此必须先删；同时按自身资源字段
        // 与 invitation 归属双重匹配，兼容历史上可能存在的不一致数据。
        jdbc.update("""
                DELETE FROM resource_join_request
                WHERE (resource_type = ? AND resource_id = ?)
                   OR invitation_id IN (
                       SELECT id FROM resource_invitation
                       WHERE resource_type = ? AND resource_id = ?
                   )
                """, resourceType, resourceId, resourceType, resourceId);
        jdbc.update("DELETE FROM resource_invitation WHERE resource_type = ? AND resource_id = ?",
                resourceType, resourceId);
        jdbc.update("DELETE FROM ownership_transfer WHERE resource_type = ? AND resource_id = ?",
                resourceType, resourceId);
    }

    /** 清除知识库派生图片元数据，并删除已不再被其他本地资源引用的内容中心文件。 */
    private void deleteDerivedImageRows(long kbId) {
        List<Long> fileIds = jdbc.queryForList("""
                SELECT DISTINCT content_id FROM derived_image_asset
                WHERE source_kb_id = ? AND content_id IS NOT NULL
                """, Long.class, kbId);
        for (Long fileId : fileIds) {
            if (fileId == null || fileId <= 0) continue;
            Integer attachmentReferences = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM attachment
                    LEFT JOIN attachment_blob ab ON ab.id = attachment.blob_id
                    WHERE COALESCE(ab.content_center_file_id,
                                   attachment.content_center_file_id) = ?
                    """, Integer.class, fileId);
            Integer otherDerivedReferences = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM derived_image_asset
                    WHERE content_id = ? AND source_kb_id <> ?
                    """, Integer.class, fileId, kbId);
            if ((attachmentReferences == null || attachmentReferences == 0)
                    && (otherDerivedReferences == null || otherDerivedReferences == 0)) {
                if (attachmentStorage == null) {
                    throw new IllegalStateException("content-center attachment storage is unavailable");
                }
                attachmentStorage.delete(fileId);
            }
        }
        jdbc.update("""
                DELETE FROM derived_image_summary
                WHERE asset_id IN (
                    SELECT id FROM derived_image_asset WHERE source_kb_id = ?
                )
                """, kbId);
        jdbc.update("DELETE FROM derived_image_asset WHERE source_kb_id = ?", kbId);
    }

    /**
     * V23 引入了按物理索引划分的目标行，并通过外键指向
     * 旧的聚合任务。因此先清理聚合任务会导致失败，影响
     * 所有已经扇出到某个索引版本的资源。
     */
    private void deleteIndexingJobs(String resourceType, long resourceId) {
        jdbc.update("""
                DELETE FROM indexing_job_target
                WHERE job_id IN (
                    SELECT id FROM indexing_job
                    WHERE resource_type = ? AND resource_id = ?
                )
                """, resourceType, resourceId);
        jdbc.update("DELETE FROM indexing_job WHERE resource_type = ? AND resource_id = ?",
                resourceType, resourceId);
    }

    public String newJobId() {
        return "recycle-bin-" + UUID.randomUUID();
    }
}
