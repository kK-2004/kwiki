package com.kwiki.indexing.job;

/**
 * Port used by content services to record indexing work inside the same
 * transaction that publishes or archives a resource. The MySQL-backed
 * implementation (indexing_job table) keeps jobs durable; callers never
 * interact with the queue directly.
 *
 * <p>Versioned variants carry the lifecycle version observed at enqueue time so
 * the worker can fence stale work after an archive/restore transition.</p>
 */
public interface IndexingJobEnqueuer {

    void enqueuePageUpsert(long pageId, long revisionId);

    void enqueuePageDelete(long pageId);

    void enqueueAttachmentUpsert(long attachmentId);

    void enqueueAttachmentDelete(long attachmentId);

    default void enqueuePageUpsert(long pageId, long revisionId, long expectedLifecycleVersion) {
        enqueuePageUpsert(pageId, revisionId);
    }

    default void enqueuePageDelete(long pageId, long expectedLifecycleVersion) {
        enqueuePageDelete(pageId);
    }

    default void enqueueAttachmentDelete(long attachmentId, long expectedLifecycleVersion) {
        enqueueAttachmentDelete(attachmentId);
    }

    /** Whole-knowledge-base chunk deletion (archive outbox / retry). */
    default void enqueueKnowledgeBaseDelete(long kbId, long expectedLifecycleVersion) {
        // no-op for in-memory/test implementations
    }
}
