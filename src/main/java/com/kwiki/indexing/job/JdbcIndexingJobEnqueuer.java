package com.kwiki.indexing.job;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

/**
 * MySQL-backed enqueuer. Called inside the publishing/archiving transaction, so a job
 * row exists if and only if the content change commits. The idempotency key
 * (resourceType:resourceId:revisionId:operation) makes repeated enqueueing safe;
 * completed/failed rows of the same key are re-opened, live pending jobs are kept.
 */
@Repository
public class JdbcIndexingJobEnqueuer implements IndexingJobEnqueuer {

    private final JdbcOperations jdbc;

    public JdbcIndexingJobEnqueuer(ObjectProvider<JdbcOperations> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @Override
    public void enqueuePageUpsert(long pageId, long revisionId) {
        enqueue("UPSERT", "PAGE", pageId, revisionId);
    }

    @Override
    public void enqueuePageDelete(long pageId) {
        enqueue("DELETE", "PAGE", pageId, null);
    }

    @Override
    public void enqueueAttachmentUpsert(long attachmentId) {
        enqueue("UPSERT", "ATTACHMENT", attachmentId, null);
    }

    @Override
    public void enqueueAttachmentDelete(long attachmentId) {
        enqueue("DELETE", "ATTACHMENT", attachmentId, null);
    }

    private void enqueue(String jobType, String resourceType, long resourceId, Long revisionId) {
        if (jdbc == null) {
            return;
        }
        String idempotencyKey = resourceType + ":" + resourceId + ":"
                + (revisionId == null ? "-" : revisionId) + ":" + jobType;
        jdbc.update("""
                        INSERT INTO indexing_job
                            (job_type, resource_type, resource_id, revision_id, idempotency_key,
                             state, attempts, max_attempts)
                        VALUES (?, ?, ?, ?, ?, 'PENDING', 0, 8)
                        ON DUPLICATE KEY UPDATE
                            state = IF(state IN ('COMPLETED', 'FAILED'), 'PENDING', state),
                            updated_at = CURRENT_TIMESTAMP(6)
                        """,
                jobType, resourceType, resourceId, revisionId, idempotencyKey);
    }
}
