package com.kwiki.indexing.job;

import java.util.EnumSet;
import java.util.Set;

/**
 * Indexing-job lifecycle. Jobs are enqueued PENDING, leased by workers, retried with
 * bounded backoff from RETRY_WAIT, reclaimed from expired leases, completed once, or
 * marked FAILED after exhausting attempts. FAILED jobs only move again through an
 * explicit administrator retry.
 */
public enum IndexingJobState {
    PENDING,
    LEASED,
    RETRY_WAIT,
    COMPLETED,
    FAILED;

    private static final java.util.Map<IndexingJobState, Set<IndexingJobState>> ALLOWED =
            java.util.Map.of(
                    PENDING, EnumSet.of(LEASED),
                    LEASED, EnumSet.of(COMPLETED, RETRY_WAIT, FAILED, PENDING),
                    RETRY_WAIT, EnumSet.of(LEASED, PENDING),
                    COMPLETED, EnumSet.noneOf(IndexingJobState.class),
                    FAILED, EnumSet.of(PENDING));

    public boolean canTransitionTo(IndexingJobState next) {
        return ALLOWED.get(this).contains(next);
    }

    /** Central transition guard; every mutation must go through it. */
    public IndexingJobState requireTransitionTo(IndexingJobState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "illegal indexing job transition " + this + " -> " + next);
        }
        return next;
    }
}
