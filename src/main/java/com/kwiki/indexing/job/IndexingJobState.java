package com.kwiki.indexing.job;

import java.util.EnumSet;
import java.util.Set;

/**
 * 索引构建任务的生命周期。任务以 PENDING 入队，由工作线程租用，从 RETRY_WAIT
 * 以有界退避重试，从过期租约中被收回，只完成一次，或在
 * 尝试次数耗尽后被标记为 FAILED。FAILED 的任务只能通过
 * 管理员的显式重试再次流转。
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

    /** 集中的状态转移守卫；每一次状态变更都必须经过它。 */
    public IndexingJobState requireTransitionTo(IndexingJobState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "illegal indexing job transition " + this + " -> " + next);
        }
        return next;
    }
}
