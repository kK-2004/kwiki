package com.kwiki.indexing.job;

/**
 * 内容服务用于在「发布或归档资源」的同一事务内
 * 记录索引构建工作的端口。基于 MySQL 的
 * 实现（indexing_job 表）使任务持久化；调用方绝不
 * 直接与队列交互。
 *
 * <p>带版本号的变体携带入队时观测到的生命周期版本，以便
 * 工作线程在归档/恢复切换之后能够防护过期的任务。</p>
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

    /** 整知识库范围的分块删除（归档发件箱 / 重试）。 */
    default void enqueueKnowledgeBaseDelete(long kbId, long expectedLifecycleVersion) {
        // 内存/测试实现中为空操作
    }
}
