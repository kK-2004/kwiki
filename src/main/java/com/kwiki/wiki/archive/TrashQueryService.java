package com.kwiki.wiki.archive;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.domain.ArchiveBatch;
import com.kwiki.wiki.domain.KnowledgeBase;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.persistence.ArchiveBatchRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Paged recycle-bin listing. Authorization is checked against the saved
 * management permission over the batch scope — never through the
 * ACTIVE-requiring resource loaders, because everything listed here is
 * archived by definition. Workspace view (no kbId): archived knowledge bases;
 * knowledge-base view: page batches inside that base.
 */
@Service
public class TrashQueryService {

    public record TrashItem(long batchId, String batchUuid, String resourceType,
                            long rootResourceId, String title, String operatorName,
                            String archivedAt, String purgeAfter, boolean restorable,
                            String indexSyncStatus, int itemCount) {}

    public record TrashPage(List<TrashItem> items, Long nextCursor) {}

    private final ArchiveBatchRepository batches;
    private final WikiPageRepository pages;
    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeBaseAuthorizationService authorization;
    private final JdbcOperations jdbc;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TrashQueryService(ArchiveBatchRepository batches,
                             WikiPageRepository pages,
                             KnowledgeBaseRepository knowledgeBases,
                             KnowledgeBaseAuthorizationService authorization,
                             ObjectProvider<JdbcOperations> jdbc) {
        this(batches, pages, knowledgeBases, authorization, jdbc, Clock.systemUTC());
    }

    public TrashQueryService(ArchiveBatchRepository batches,
                             WikiPageRepository pages,
                             KnowledgeBaseRepository knowledgeBases,
                             KnowledgeBaseAuthorizationService authorization,
                             ObjectProvider<JdbcOperations> jdbc,
                             Clock clock) {
        this.batches = batches;
        this.pages = pages;
        this.knowledgeBases = knowledgeBases;
        this.authorization = authorization;
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
        this.clock = clock;
    }

    public TrashPage list(CurrentUser user, Long kbId, String resourceType,
                          Long cursor, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), 50);
        List<ArchiveBatch> rows;
        if (kbId == null) {
            // Workspace view: only archived knowledge bases the caller manages.
            rows = batches.findByStateOrderByArchivedAtDesc(
                    ArchiveBatch.STATE_ARCHIVED, PageRequest.of(0, pageSize * 8));
            List<ArchiveBatch> visible = new ArrayList<>();
            for (ArchiveBatch batch : rows) {
                if (!ArchiveBatch.SCOPE_KNOWLEDGE_BASE.equals(batch.getScopeType())) {
                    continue;
                }
                if (cursor != null && batch.getId() >= cursor) {
                    continue; // stable id-descending pagination
                }
                if (user.admin()
                        || authorization.can(user, batch.getKbId(),
                                WikiAction.ARCHIVE_KNOWLEDGE_BASE)) {
                    visible.add(batch);
                    if (visible.size() >= pageSize + 1) {
                        break;
                    }
                }
            }
            return toPage(visible, pageSize);
        }
        // Knowledge-base view: page batches inside the base.
        authorization.require(user, kbId, WikiAction.ARCHIVE_PAGE);
        rows = batches.findByStateAndKbIdOrderByArchivedAtDesc(
                ArchiveBatch.STATE_ARCHIVED, kbId, PageRequest.of(0, pageSize * 8));
        List<ArchiveBatch> visible = new ArrayList<>();
        for (ArchiveBatch batch : rows) {
            if (resourceType != null && !resourceType.isBlank()
                    && !batch.getScopeType().equalsIgnoreCase(resourceType)) {
                continue;
            }
            if (cursor != null && batch.getId() >= cursor) {
                continue; // stable id-descending pagination
            }
            visible.add(batch);
            if (visible.size() >= pageSize + 1) {
                break;
            }
        }
        return toPage(visible, pageSize);
    }

    private TrashPage toPage(List<ArchiveBatch> rows, int pageSize) {
        boolean hasNext = rows.size() > pageSize;
        List<ArchiveBatch> page = hasNext ? rows.subList(0, pageSize) : rows;
        List<TrashItem> items = new ArrayList<>();
        for (ArchiveBatch batch : page) {
            items.add(new TrashItem(
                    batch.getId(),
                    batch.getBatchUuid(),
                    batch.getScopeType(),
                    batch.getRootResourceId(),
                    resolveTitle(batch),
                    resolveOperatorName(batch.getOperatorId()),
                    batch.getArchivedAt().toString(),
                    batch.getPurgeAfter().toString(),
                    clock.instant().isBefore(batch.getPurgeAfter()),
                    batch.getIndexSyncStatus(),
                    batch.getItemCount()));
        }
        Long nextCursor = hasNext && !page.isEmpty()
                ? page.get(page.size() - 1).getId()
                : null;
        return new TrashPage(items, nextCursor);
    }

    private String resolveTitle(ArchiveBatch batch) {
        if (ArchiveBatch.SCOPE_KNOWLEDGE_BASE.equals(batch.getScopeType())) {
            return knowledgeBases.findById(batch.getRootResourceId())
                    .map(KnowledgeBase::getName)
                    .orElse("已删除的知识库");
        }
        return pages.findById(batch.getRootResourceId())
                .map(WikiPage::getTitle)
                .orElse("已删除的页面");
    }

    private String resolveOperatorName(long operatorId) {
        if (jdbc == null) {
            return String.valueOf(operatorId);
        }
        try {
            return jdbc.queryForObject(
                    "SELECT username FROM app_user WHERE id = ?", String.class, operatorId);
        } catch (Exception e) {
            return String.valueOf(operatorId);
        }
    }
}
