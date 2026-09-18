package com.kwiki.wiki.archive;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.WikiAction;
import com.kwiki.wiki.domain.ArchiveBatch;
import com.kwiki.wiki.persistence.ArchiveBatchRepository;

import org.springframework.stereotype.Service;

/**
 * 回收站用户主动永久删除的授权门面。物理清除本身由
 * {@link RecycleBinCleanupService} 执行，以便与定时清理复用同一套索引和外键处理。
 */
@Service
public class TrashPurgeService {

    public record PurgeResult(long batchId, int purgedItems, String message) {}

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
        ArchiveBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new com.kk2004.common.exception.NotFoundException(
                        "archive batch not found"));
        WikiAction action = ArchiveBatch.SCOPE_KNOWLEDGE_BASE.equals(batch.getScopeType())
                ? WikiAction.ARCHIVE_KNOWLEDGE_BASE
                : WikiAction.ARCHIVE_PAGE;
        authorization.require(user, batch.getKbId(), action);

        RecycleBinCleanupService.PurgeResult result = cleanup.purgeNow(batchId);
        return new PurgeResult(result.batchId(), result.purgedItems(), "已永久删除，内容不可恢复");
    }
}
