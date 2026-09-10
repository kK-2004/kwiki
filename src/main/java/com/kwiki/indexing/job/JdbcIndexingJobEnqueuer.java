package com.kwiki.indexing.job;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

/**
 * MySQL-backed enqueuer. Called inside the publishing/archiving transaction, so a job
 * row exists if and only if the content change commits. The idempotency key
 * (resourceType:resourceId:revisionId:operation) makes repeated enqueueing safe;
 * completed/failed rows of the same key are re-opened, live pending jobs are kept.
 * The optional expected lifecycle version fences the worker against stale work
 * after an archive/restore transition bumps the resource version.
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
