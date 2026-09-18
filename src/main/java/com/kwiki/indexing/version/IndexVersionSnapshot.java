package com.kwiki.indexing.version;

/**
 * 一个物理索引版本的不可变状态快照：持久化行的值对象，也是全部
 * 状态推导与迁移函数的输入/输出。凭据永不进入快照。
 *
 * @param activeRun          该版本存在活动（PENDING/RUNNING/PAUSED）重建或补齐 run
 * @param switchPreparing    该版本正在切换准备（范围尾扫/事件重放/屏障收敛中）
 */
public record IndexVersionSnapshot(
        int versionNumber,
        String physicalName,
        long configRevision,
        Long builtConfigRevision,
        IndexBuildState buildState,
        IndexCatchupStatus catchupStatus,
        boolean writeEnabled,
        boolean selected,
        boolean pipelineSupported,
        boolean deleted,
        String needsAttentionReason,
        boolean activeRun,
        boolean switchPreparing) {

    /** dirty 是派生事实：从未构建或配置修订高于最近成功构建修订。 */
    public boolean dirty() {
        return builtConfigRevision == null || builtConfigRevision != configRevision;
    }

    /** 在线（别名目标）、写入启用、构建/补齐运行中或已删除的版本禁止原地编辑。 */
    public boolean editable() {
        return !selected && !writeEnabled && !activeRun && !switchPreparing && !deleted;
    }

    /** 只有已构建、配置不脏、流水线受支持且数据追平的版本才允许被选择切换。 */
    public boolean selectable() {
        return !deleted && !dirty() && buildState == IndexBuildState.BUILT
                && pipelineSupported && catchupStatus == IndexCatchupStatus.CURRENT
                && needsAttentionReason == null;
    }

    public IndexVersionSnapshot withNeedsAttention(String reason) {
        return new IndexVersionSnapshot(versionNumber, physicalName, configRevision,
                builtConfigRevision, buildState, catchupStatus, writeEnabled, selected,
                pipelineSupported, deleted, reason, activeRun, switchPreparing);
    }
}
