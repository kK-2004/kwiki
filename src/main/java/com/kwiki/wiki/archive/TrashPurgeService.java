package com.kwiki.wiki.archive;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.domain.ArchiveBatch;
import com.kwiki.wiki.persistence.ArchiveBatchRepository;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 回收站用户主动永久删除的授权门面。物理清除本身由
 * {@link RecycleBinCleanupService} 执行，以便与定时清理复用同一套索引和外键处理。
 */
@Service
public class TrashPurgeService {

    public record PurgeResult(long batchId, int purgedItems, String message) {}
    public record BatchPurgeResult(int deletedBatches, int purgedItems, String message) {}

    private final ArchiveBatchRepository batches;
    private final KnowledgeBaseAuthorizationService authorization;
    private final RecycleBinCleanupService cleanup;

    public TrashPurgeService(ArchiveBatchRepository batches,
                             KnowledgeBaseAuthorizationService authorization,
                             RecycleBinCleanupService cleanup) {
        this.batches = batches;
        this.authorization = authorization;
        this.cleanup = cleanup;
    }

    public PurgeResult purge(CurrentUser user, long batchId) {
        ArchiveBatch batch = requireBatch(batchId);
        authorize(user, batch);

        RecycleBinCleanupService.PurgeResult result = cleanup.purgeNow(batchId);
        return new PurgeResult(result.batchId(), result.purgedItems(), "已永久删除，内容不可恢复");
    }

    /**
     * 批量永久删除。先完整加载并授权全部批次，避免权限错误造成部分删除；
     * 页面批次优先于知识库批次，防止知识库递归清理连带移除尚待处理的页面批次。
     */
    public BatchPurgeResult purgeAll(CurrentUser user, List<Long> batchIds) {
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>();
        if (batchIds != null) {
            for (Long batchId : batchIds) {
                if (batchId == null || batchId <= 0) {
                    throw new IllegalArgumentException("batchIds contains an invalid id");
                }
                uniqueIds.add(batchId);
            }
        }
        if (uniqueIds.isEmpty()) {
            throw new IllegalArgumentException("batchIds is required");
        }
        if (uniqueIds.size() > 100) {
            throw new IllegalArgumentException("at most 100 batches may be deleted at once");
        }

        List<ArchiveBatch> selected = new ArrayList<>(uniqueIds.size());
        for (Long batchId : uniqueIds) {
            ArchiveBatch batch = requireBatch(batchId);
            authorize(user, batch);
            selected.add(batch);
        }
        selected.sort(Comparator
                .comparingInt((ArchiveBatch batch) ->
                        ArchiveBatch.SCOPE_KNOWLEDGE_BASE.equals(batch.getScopeType()) ? 1 : 0)
                .thenComparingLong(ArchiveBatch::getId));

        int purgedItems = 0;
        for (ArchiveBatch batch : selected) {
            purgedItems += cleanup.purgeNow(batch.getId()).purgedItems();
        }
        return new BatchPurgeResult(selected.size(), purgedItems,
                "已永久删除 " + selected.size() + " 项，内容不可恢复");
    }

    private ArchiveBatch requireBatch(long batchId) {
        return batches.findById(batchId)
                .orElseThrow(() -> new com.kk2004.common.exception.NotFoundException(
                        "archive batch not found"));
    }

    private void authorize(CurrentUser user, ArchiveBatch batch) {
        WikiAction action = ArchiveBatch.SCOPE_KNOWLEDGE_BASE.equals(batch.getScopeType())
                ? WikiAction.ARCHIVE_KNOWLEDGE_BASE
                : WikiAction.ARCHIVE_PAGE;
        authorization.require(user, batch.getKbId(), action);
    }
}
