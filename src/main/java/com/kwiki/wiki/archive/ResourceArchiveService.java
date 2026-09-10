package com.kwiki.wiki.archive;

import com.kwiki.indexing.job.IndexingJobEnqueuer;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.access.ScopeVersionService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.domain.ArchiveBatch;
import com.kwiki.wiki.domain.ArchiveBatchItem;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.domain.KnowledgeBase;
import com.kwiki.wiki.domain.SourceDocument;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.persistence.ArchiveBatchItemRepository;
import com.kwiki.wiki.persistence.ArchiveBatchRepository;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseRepository;
import com.kwiki.wiki.persistence.SourceDocumentRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import com.kwiki.wiki.archive.TransactionRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 覆盖所有 wiki 界面（页面详情、树、知识库）的统一归档入口。
 * 页面归档会把 ACTIVE 子树及其独占的
 * 可索引来源快照进一个可恢复批次；知识库归档则覆盖
 * 所有仍然有效的页面与已存储附件。此前相互独立的批次
 * 保留各自的窗口与状态 —— 恢复知识库绝不会
 * 让它们复活。
 *
 * <p>事务形态：先校验完整的管理权限范围（不产生部分
 * 归档），然后在一个事务中提交权威的逻辑归档与删除
 * 发件箱，最后才尝试同步的 ES
 * 删除。ES 尝试会报告 SYNCED/PENDING；无论如何，
 * 权威的生命周期过滤都已排除已归档的内容。</p>
 */
@Service
public class ResourceArchiveService {

    /** 7 天保留窗口（168 小时），以 UTC Instant 存储。 */
    public static final int RETENTION_HOURS = 168;

    public record ArchiveResult(long batchId, String batchUuid, int itemCount,
                                Instant archivedAt, Instant purgeAfter, String indexSyncStatus) {}

    public static final class ExpiredBatchException extends RuntimeException {
        public ExpiredBatchException(String message) {
            super(message);
        }
    }

    public static final class BatchConflictException extends RuntimeException {
        public BatchConflictException(String message) {
            super(message);
        }
    }

    private final WikiPageRepository pages;
    private final KnowledgeBaseRepository knowledgeBases;
    private final AttachmentRepository attachments;
    private final SourceDocumentRepository sourceDocuments;
    private final ArchiveBatchRepository batches;
    private final ArchiveBatchItemRepository batchItems;
    private final KnowledgeBaseAuthorizationService authorization;
    private final ResourceAuthorizationService resources;
    private final ScopeVersionService scopeVersions;
    private final IndexingJobEnqueuer indexingJobs;
    private final ArchiveIndexSyncService indexSync;
    private final TransactionRunner transactions;
    private final JdbcOperations jdbc;
    private final Clock clock;

    @Autowired
    public ResourceArchiveService(WikiPageRepository pages,
                                  KnowledgeBaseRepository knowledgeBases,
                                  AttachmentRepository attachments,
                                  SourceDocumentRepository sourceDocuments,
                                  ArchiveBatchRepository batches,
                                  ArchiveBatchItemRepository batchItems,
                                  KnowledgeBaseAuthorizationService authorization,
                                  ResourceAuthorizationService resources,
                                  ScopeVersionService scopeVersions,
                                  IndexingJobEnqueuer indexingJobs,
                                  ArchiveIndexSyncService indexSync,
                                  TransactionRunner transactions,
                                  ObjectProvider<JdbcOperations> jdbc) {
        this(pages, knowledgeBases, attachments, sourceDocuments, batches, batchItems,
                authorization, resources, scopeVersions, indexingJobs, indexSync,
                transactions, jdbc, Clock.systemUTC());
    }

    public ResourceArchiveService(WikiPageRepository pages,
                                  KnowledgeBaseRepository knowledgeBases,
                                  AttachmentRepository attachments,
                                  SourceDocumentRepository sourceDocuments,
                                  ArchiveBatchRepository batches,
                                  ArchiveBatchItemRepository batchItems,
                                  KnowledgeBaseAuthorizationService authorization,
                                  ResourceAuthorizationService resources,
                                  ScopeVersionService scopeVersions,
                                  IndexingJobEnqueuer indexingJobs,
                                  ArchiveIndexSyncService indexSync,
                                  TransactionRunner transactions,
                                  ObjectProvider<JdbcOperations> jdbc,
                                  Clock clock) {
        this.pages = pages;
        this.knowledgeBases = knowledgeBases;
        this.attachments = attachments;
        this.sourceDocuments = sourceDocuments;
        this.batches = batches;
        this.batchItems = batchItems;
        this.authorization = authorization;
        this.resources = resources;
        this.scopeVersions = scopeVersions;
        this.indexingJobs = indexingJobs;
        this.indexSync = indexSync;
        this.transactions = transactions;
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 归档
    // ------------------------------------------------------------------

    /** 幂等：对已归档页面再次归档会返回其现有窗口。 */
    public ArchiveResult archivePage(CurrentUser user, long kbId, long pageId) {
        KnowledgeBase kb = knowledgeBases.findById(kbId)
                .filter(candidate -> !candidate.isArchived())
                .orElseThrow(() -> new com.kk2004.common.exception.NotFoundException(
                        "knowledge base not found"));
        WikiPage root = pages.findByIdAndStatus(pageId, WikiPage.STATUS_ACTIVE)
                .orElseThrow(() -> new com.kk2004.common.exception.NotFoundException("page not found"));
        if (root.getKbId() != kbId) {
            throw new com.kk2004.common.exception.NotFoundException("page not found");
        }
        List<WikiPage> subtree = collectActiveSubtree(kbId, pageId);
        // 在任何写入之前先做全范围的管理权限校验：一次失败即拒绝
        // 整个操作，绝不产生部分归档。
        authorization.require(user, kbId, WikiAction.ARCHIVE_PAGE);
        for (WikiPage page : subtree) {
            resources.requireInKnowledgeBase(user, kbId, page.getId(), ResourceAction.MANAGE);
        }

        ArchiveBatch batch = transactions.inTransactionReturning(() -> {
            Optional<ArchiveBatch> existing = existingLiveBatch(ArchiveBatch.SCOPE_PAGE, pageId);
            if (existing.isPresent()) {
                return existing.get(); // 重试时定时器永不重置
            }
            Instant now = clock.instant();
            List<Attachment> exclusiveSources = exclusiveSourceAttachments(subtree);
            ArchiveBatch created = batches.save(new ArchiveBatch(
                    UUID.randomUUID().toString(), ArchiveBatch.SCOPE_PAGE, pageId, kbId,
                    user.id(), now, now.plus(RETENTION_HOURS, ChronoUnit.HOURS),
                    subtree.size() + exclusiveSources.size(), ArchiveBatch.ORIGIN_NORMAL));
            for (WikiPage page : subtree) {
                page.archive();
                page.bumpLifecycleVersion();
                pages.save(page);
                batchItems.save(new ArchiveBatchItem(
                        created.getId(), ArchiveBatchItem.RESOURCE_PAGE, page.getId(),
                        kbId, ArchiveBatchItem.PRIOR_ACTIVE,
                        page.getId().equals(pageId) ? root.getParentId() : page.getParentId(),
                        page.getLifecycleVersion()));
                indexingJobs.enqueuePageDelete(page.getId(), page.getLifecycleVersion());
            }
            for (Attachment attachment : exclusiveSources) {
                attachment.archive();
                attachments.save(attachment);
                batchItems.save(new ArchiveBatchItem(
                        created.getId(), ArchiveBatchItem.RESOURCE_ATTACHMENT,
                        attachment.getId(), kbId, ArchiveBatchItem.PRIOR_ACTIVE, null, 0L));
                indexingJobs.enqueueAttachmentDelete(attachment.getId(), kb.getLifecycleVersion());
            }
            scopeVersions.bump(kbId);
            return created;
        });
        return finishArchive(batch);
    }

    /** 幂等：对已归档知识库再次归档会返回其现有窗口。 */
    public ArchiveResult archiveKnowledgeBase(CurrentUser user, long kbId) {
        authorization.require(user, kbId, WikiAction.ARCHIVE_KNOWLEDGE_BASE);
        KnowledgeBase kb = knowledgeBases.findById(kbId)
                .filter(candidate -> !candidate.isArchived())
                .orElseThrow(() -> new com.kk2004.common.exception.NotFoundException(
                        "knowledge base not found"));

        ArchiveBatch batch = transactions.inTransactionReturning(() -> {
            Optional<ArchiveBatch> existing = existingLiveBatch(
                    ArchiveBatch.SCOPE_KNOWLEDGE_BASE, kbId);
            if (existing.isPresent()) {
                return existing.get();
            }
            List<WikiPage> activePages = pages
                    .findByKbIdAndStatusOrderByParentIdAscSiblingOrderAsc(
                            kbId, WikiPage.STATUS_ACTIVE);
            List<Attachment> storedAttachments = attachments
                    .findByKbIdAndStatusOrderByIdDesc(kbId, Attachment.STATUS_STORED);
            Instant now = clock.instant();
            ArchiveBatch created = batches.save(new ArchiveBatch(
                    UUID.randomUUID().toString(), ArchiveBatch.SCOPE_KNOWLEDGE_BASE, kbId,
                    kbId, user.id(), now, now.plus(RETENTION_HOURS, ChronoUnit.HOURS),
                    activePages.size() + storedAttachments.size() + 1,
                    ArchiveBatch.ORIGIN_NORMAL));
            KnowledgeBase managed = knowledgeBases.findById(kbId).orElseThrow();
            managed.archive();
            managed.bumpLifecycleVersion();
            knowledgeBases.save(managed);
            batchItems.save(new ArchiveBatchItem(
                    created.getId(), ArchiveBatchItem.RESOURCE_KNOWLEDGE_BASE, kbId, kbId,
                    ArchiveBatchItem.PRIOR_ACTIVE, null, managed.getLifecycleVersion()));
            indexingJobs.enqueueKnowledgeBaseDelete(kbId, managed.getLifecycleVersion());
            for (WikiPage page : activePages) {
                page.archive();
                page.bumpLifecycleVersion();
                pages.save(page);
                batchItems.save(new ArchiveBatchItem(
                        created.getId(), ArchiveBatchItem.RESOURCE_PAGE, page.getId(),
                        kbId, ArchiveBatchItem.PRIOR_ACTIVE, page.getParentId(),
                        page.getLifecycleVersion()));
                indexingJobs.enqueuePageDelete(page.getId(), page.getLifecycleVersion());
            }
            for (Attachment attachment : storedAttachments) {
                attachment.archive();
                attachments.save(attachment);
                batchItems.save(new ArchiveBatchItem(
                        created.getId(), ArchiveBatchItem.RESOURCE_ATTACHMENT,
                        attachment.getId(), kbId, ArchiveBatchItem.PRIOR_ACTIVE, null,
                        managed.getLifecycleVersion()));
                indexingJobs.enqueueAttachmentDelete(attachment.getId(),
                        managed.getLifecycleVersion());
            }
            scopeVersions.bump(kbId);
            return created;
        });
        return finishArchive(batch);
    }

    private ArchiveResult finishArchive(ArchiveBatch batch) {
        String syncStatus = indexSync.attemptSync(batch);
        return new ArchiveResult(batch.getId(), batch.getBatchUuid(), batch.getItemCount(),
                batch.getArchivedAt(), batch.getPurgeAfter(), syncStatus);
    }

    /**
     * 独占的可索引来源：导入文档，其唯一的引用
     * 页面（经由 source_document 或持久化的媒体引用）都位于
     * 被归档的子树之内。与子树外任何对象共享的附件保留其
     * 文件/元数据；被归档的页面只是通过生命周期过滤
     * 不再是它们的授权检索来源。
     */
    private List<Attachment> exclusiveSourceAttachments(List<WikiPage> subtree) {
        List<Long> pageIds = subtree.stream().map(WikiPage::getId).toList();
        HashSet<Long> archivedIds = new HashSet<>(pageIds);
        LinkedHashMap<Long, Attachment> exclusive = new LinkedHashMap<>();
        for (SourceDocument doc : sourceDocuments.findByPageIdIn(pageIds)) {
            Long attachmentId = doc.getAttachmentId();
            if (attachmentId == null || exclusive.containsKey(attachmentId)) {
                continue;
            }
            boolean referencedOutside = sourceDocuments.findByAttachmentId(attachmentId).stream()
                    .anyMatch(other -> !archivedIds.contains(other.getPageId()))
                    || mediaReferencedOutside(attachmentId, archivedIds);
            if (!referencedOutside) {
                attachments.findById(attachmentId)
                        .filter(Attachment::isStored)
                        .ifPresent(attachment -> exclusive.put(attachmentId, attachment));
            }
        }
        return List.copyOf(exclusive.values());
    }

    private boolean mediaReferencedOutside(long attachmentId, HashSet<Long> archivedIds) {
        if (jdbc == null) {
            return false;
        }
        List<Long> referencingPages = jdbc.query(
                "SELECT DISTINCT page_id FROM page_revision_media WHERE attachment_id = ?",
                (rs, row) -> rs.getLong(1), attachmentId);
        return referencingPages.stream().anyMatch(pageId -> !archivedIds.contains(pageId));
    }

    private Optional<ArchiveBatch> existingLiveBatch(String scopeType, long rootResourceId) {
        return batches.findFirstByScopeTypeAndRootResourceIdAndStateNotOrderByArchivedAtDesc(
                        scopeType, rootResourceId, ArchiveBatch.STATE_PURGED)
                .filter(batch -> ArchiveBatch.STATE_ARCHIVED.equals(batch.getState()));
    }

    /**
     * 从 {@code rootPageId} 开始的 ACTIVE 子树快照：后代节点
     * 会穿过任意状态的中间节点被收集，因此历史上
     * 分支中途被归档的子节点仍被覆盖。根节点本身
     * 只有处于 ACTIVE 时才被包含。
     */
    public List<WikiPage> collectActiveSubtree(long kbId, long rootPageId) {
        List<WikiPage> all = pages.findByKbId(kbId);
        Map<Long, List<WikiPage>> children = new LinkedHashMap<>();
        for (WikiPage page : all) {
            if (page.getParentId() != null) {
                children.computeIfAbsent(page.getParentId(), key -> new ArrayList<>()).add(page);
            }
        }
        List<WikiPage> result = new ArrayList<>();
        var queue = new java.util.ArrayDeque<Long>();
        queue.add(rootPageId);
        pages.findByIdAndStatus(rootPageId, WikiPage.STATUS_ACTIVE).ifPresent(result::add);
        while (!queue.isEmpty()) {
            long current = queue.poll();
            for (WikiPage page : children.getOrDefault(current, List.of())) {
                if (WikiPage.STATUS_ACTIVE.equals(page.getStatus())) {
                    result.add(page);
                }
                queue.add(page.getId());
            }
        }
        result.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        return result;
    }

    // ------------------------------------------------------------------
    // 恢复
    // ------------------------------------------------------------------

    public record RestoreResult(long batchId, String scopeType, int restoredItems,
                                int relocatedToRoot, String message) {}

    public RestoreResult restore(CurrentUser user, long batchId) {
        ArchiveBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new com.kk2004.common.exception.NotFoundException(
                        "archive batch not found"));
        // 针对已归档范围本身做管理权限校验；刻意
        // 不使用要求 ACTIVE 的资源加载器。
        if (ArchiveBatch.SCOPE_KNOWLEDGE_BASE.equals(batch.getScopeType())) {
            authorization.require(user, batch.getKbId(), WikiAction.ARCHIVE_KNOWLEDGE_BASE);
        } else {
            authorization.require(user, batch.getKbId(), WikiAction.ARCHIVE_PAGE);
        }

        return transactions.inTransactionReturning(() -> {
            if (jdbc != null) {
                // 在批次行上把恢复与每日清理串行化。
                jdbc.queryForObject("SELECT id FROM archive_batch WHERE id = ? FOR UPDATE",
                        Long.class, batchId);
            }
            ArchiveBatch locked = batches.findById(batchId).orElseThrow();
            if (ArchiveBatch.STATE_PURGED.equals(locked.getState())) {
                throw new ExpiredBatchException("回收站批次已被清理，无法恢复");
            }
            if (!ArchiveBatch.STATE_ARCHIVED.equals(locked.getState())) {
                throw new BatchConflictException("该批次已恢复");
            }
            Instant now = clock.instant();
            if (!now.isBefore(locked.getPurgeAfter())) {
                throw new ExpiredBatchException("保留期已过（7 天），无法恢复");
            }
            KnowledgeBase kb = knowledgeBases.findById(locked.getKbId()).orElseThrow();
            if (ArchiveBatch.SCOPE_PAGE.equals(locked.getScopeType()) && kb.isArchived()) {
                throw new BatchConflictException("所属知识库仍处于回收站，请先恢复知识库");
            }
            List<ArchiveBatchItem> items = batchItems.findByBatchIdOrderByIdAsc(batchId);
            int restored = 0;
            int relocated = 0;
            for (ArchiveBatchItem item : items) {
                if (item.isPurged()) {
                    continue;
                }
                switch (item.getResourceType()) {
                    case ArchiveBatchItem.RESOURCE_KNOWLEDGE_BASE -> {
                        if (kb.isArchived()
                                && item.getLifecycleVersion() == kb.getLifecycleVersion()) {
                            kb.restore();
                            kb.bumpLifecycleVersion();
                            knowledgeBases.save(kb);
                            restored++;
                        }
                    }
                    case ArchiveBatchItem.RESOURCE_PAGE -> {
                        WikiPage page = pages.findById(item.getResourceId()).orElse(null);
                        if (page == null || !page.isArchived()) {
                            continue; // 已在别处恢复，或已被物理删除
                        }
                        if (page.getLifecycleVersion() != item.getLifecycleVersion()) {
                            continue; // 快照之后版本已变化
                        }
                        Long priorParent = item.getPriorParentId();
                        boolean parentValid = priorParent != null
                                && pages.findByIdAndStatus(priorParent, WikiPage.STATUS_ACTIVE)
                                        .filter(candidate ->
                                                candidate.getKbId() == locked.getKbId())
                                        .isPresent();
                        if (!parentValid) {
                            page.moveTo(null, nextRootSiblingOrder(locked.getKbId()));
                            relocated++;
                        } else {
                            page.moveTo(priorParent, page.getSiblingOrder());
                        }
                        page.restore();
                        page.bumpLifecycleVersion();
                        pages.save(page);
                        restored++;
                        Long published = page.getCurrentPublishedRevisionId();
                        if (published != null) {
                            // 只重建已发布的修订版本；草稿永不进入索引。
                            indexingJobs.enqueuePageUpsert(page.getId(), published,
                                    page.getLifecycleVersion());
                        }
                    }
                    case ArchiveBatchItem.RESOURCE_ATTACHMENT -> {
                        Attachment attachment = attachments
                                .findById(item.getResourceId()).orElse(null);
                        if (attachment == null || !Attachment.STATUS_ARCHIVED
                                .equals(attachment.getStatus())) {
                            continue;
                        }
                        attachment.restore();
                        attachments.save(attachment);
                        restored++;
                    }
                    default -> {
                    }
                }
            }
            locked.markRestored();
            batches.save(locked);
            scopeVersions.bump(locked.getKbId());
            String message = relocated > 0
                    ? "已恢复；其中 " + relocated + " 个页面因原父级不可用，已恢复到知识库根目录"
                    : "已恢复，正在重建索引";
            return new RestoreResult(locked.getId(), locked.getScopeType(), restored,
                    relocated, message);
        });
    }

    private int nextRootSiblingOrder(long kbId) {
        return pages.findByKbIdAndParentIdAndStatusOrderBySiblingOrderAsc(kbId, null,
                        WikiPage.STATUS_ACTIVE)
                .size();
    }

    public Clock clock() {
        return clock;
    }
}
