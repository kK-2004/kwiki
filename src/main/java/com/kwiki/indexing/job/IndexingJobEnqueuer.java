package com.kwiki.indexing.job;

/**
 * Port used by content services to record indexing work inside the same
 * transaction that publishes or archives a resource. The MySQL-backed
 * implementation (indexing_job table) keeps jobs durable; callers never
 * interact with the queue directly.
 */
public interface IndexingJobEnqueuer {

    void enqueuePageUpsert(long pageId, long revisionId);

    void enqueuePageDelete(long pageId);

    void enqueueAttachmentUpsert(long attachmentId);

    void enqueueAttachmentDelete(long attachmentId);
}
