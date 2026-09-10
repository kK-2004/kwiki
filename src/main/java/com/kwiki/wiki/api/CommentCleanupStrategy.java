package com.kwiki.wiki.api;

/** 可替换的清理边界，用于处理因根评论被删除而被隐藏的回复。 */
public interface CommentCleanupStrategy {
    int cleanupBatch(int batchSize);

    /** 供定时任务/XXL-JOB 触发器使用的、感知 keyset 的批量边界。 */
    default CleanupBatch cleanupBatch(int batchSize, long afterId) {
        return new CleanupBatch(cleanupBatch(batchSize), afterId);
    }

    record CleanupBatch(int processed, long nextCursor) {}
}
