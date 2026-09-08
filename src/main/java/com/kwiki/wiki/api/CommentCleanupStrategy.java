package com.kwiki.wiki.api;

/** Replaceable cleanup boundary for replies hidden by a deleted root comment. */
public interface CommentCleanupStrategy {
    int cleanupBatch(int batchSize);

    /** Keyset-aware batch boundary used by scheduled/XXL-JOB triggers. */
    default CleanupBatch cleanupBatch(int batchSize, long afterId) {
        return new CleanupBatch(cleanupBatch(batchSize), afterId);
    }

    record CleanupBatch(int processed, long nextCursor) {}
}
