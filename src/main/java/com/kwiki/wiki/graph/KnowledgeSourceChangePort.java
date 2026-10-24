package com.kwiki.wiki.graph;

/**
 * wiki 写入侧通知图谱「知识来源已变化」的端口。graph 侧实现在业务写入的
 * 同一事务内推进该库的 source epoch 并 upsert 待重建记录；业务事务回滚时
 * epoch 一并回滚。图功能开启时，推进或标记失败会向上抛出，使业务事务整体
 * 回滚——epoch、待重建记录与业务写入保持原子，不会出现「内容已变但图谱不知道」。
 * 图功能关闭时注入 {@link #noop()} 空实现：无副作用，不会使业务写入失败。
 */
public interface KnowledgeSourceChangePort {

    /**
     * 内容变化：页面发布、回滚修订、删除，附件上传/替换/删除，
     * 页面、附件、知识库归档与恢复，回收站物理删除。在调用
     * {@code indexingJobs.enqueue*} 的同一事务中调用。
     */
    void contentChanged(long kbId);

    /** 权限作用域变化：在 {@code ScopeVersionService.bump} 推进 scope_version 的同一事务内调用。 */
    void securityChanged(long kbId);

    /** 回收站彻底删除（物理删除）：调度器将跳过静默期立即重建。 */
    void hardDeleted(long kbId);

    /** 图功能关闭时的空实现：无副作用、绝不失败。 */
    static KnowledgeSourceChangePort noop() {
        return new KnowledgeSourceChangePort() {
            @Override
            public void contentChanged(long kbId) {
            }

            @Override
            public void securityChanged(long kbId) {
            }

            @Override
            public void hardDeleted(long kbId) {
            }
        };
    }
}
