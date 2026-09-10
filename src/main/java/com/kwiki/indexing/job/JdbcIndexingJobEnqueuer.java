package com.kwiki.indexing.job;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

/**
 * 基于 MySQL 的入队器。在发布/归档事务内被调用，因此当且仅当
 * 内容变更提交时才会存在任务记录。幂等键
 * （resourceType:resourceId:revisionId:operation）使重复入队安全；
 * 同一键下已完成/已失败的行会被重新打开，仍在等待中的任务则保留。
 * 可选的预期生命周期版本用于防护工作线程，防止在归档/恢复
 * 切换使资源版本递增之后执行过期的工作。
 */
@Repository
public class JdbcIndexingJobEnqueuer implements IndexingJobEnqueuer {

    private final JdbcOperations jdbc;

    public JdbcIndexingJobEnqueuer(ObjectProvider<JdbcOperations> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @Override
    public void enqueuePageUpsert(long pageId, long revisionId) {
        enqueue("UPSERT", "PAGE", pageId, revisionId, null);
    }

    @Override
    public void enqueuePageDelete(long pageId) {
        enqueue("DELETE", "PAGE", pageId, null, null);
    }

    @Override
    public void enqueueAttachmentUpsert(long attachmentId) {
        enqueue("UPSERT", "ATTACHMENT", attachmentId, null, null);
    }

    @Override
    public void enqueueAttachmentDelete(long attachmentId) {
        enqueue("DELETE", "ATTACHMENT", attachmentId, null, null);
    }

    @Override
    public void enqueuePageUpsert(long pageId, long revisionId, long expectedLifecycleVersion) {
        enqueue("UPSERT", "PAGE", pageId, revisionId, expectedLifecycleVersion);
    }

    @Override
    public void enqueuePageDelete(long pageId, long expectedLifecycleVersion) {
        enqueue("DELETE", "PAGE", pageId, null, expectedLifecycleVersion);
    }

    @Override
    public void enqueueAttachmentDelete(long attachmentId, long expectedLifecycleVersion) {
        enqueue("DELETE", "ATTACHMENT", attachmentId, null, expectedLifecycleVersion);
    }

    @Override
    public void enqueueKnowledgeBaseDelete(long kbId, long expectedLifecycleVersion) {
        enqueue("DELETE", "KNOWLEDGE_BASE", kbId, null, expectedLifecycleVersion);
    }

    private void enqueue(String jobType, String resourceType, long resourceId,
                         Long revisionId, Long expectedLifecycleVersion) {
        if (jdbc == null) {
            return;
        }
        String idempotencyKey = resourceType + ":" + resourceId + ":"
                + (revisionId == null ? "-" : revisionId) + ":" + jobType;
        jdbc.update("""
                        INSERT INTO indexing_job
                            (job_type, resource_type, resource_id, revision_id,
                             expected_lifecycle_version, idempotency_key,
                             state, attempts, max_attempts)
                        VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, 8)
                        ON DUPLICATE KEY UPDATE
                            state = IF(state IN ('COMPLETED', 'FAILED'), 'PENDING', state),
                            expected_lifecycle_version = VALUES(expected_lifecycle_version),
                            updated_at = CURRENT_TIMESTAMP(6)
                        """,
                jobType, resourceType, resourceId, revisionId, expectedLifecycleVersion,
                idempotencyKey);
    }
}
