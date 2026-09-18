package com.kwiki.indexing.version;

/**
 * 物理索引版本的状态推导与迁移（纯函数，无副作用；持久化由服务层
 * 在事务中应用这些结果）。约束来源：manage-search-index-versions 设计
 * 决策 1/2/8——dirty 派生、构建/补齐独立、编辑限制、停用语义、
 * NEEDS_ATTENTION 对账。
 */
public final class IndexVersionStatusPolicy {

    private IndexVersionStatusPolicy() {
    }

    /** 管理端主展示状态；多个内部状态绝不拼成含混标签。 */
    public static IndexDisplayStatus displayStatus(IndexVersionSnapshot snapshot) {
        if (snapshot.needsAttentionReason() != null) {
            return IndexDisplayStatus.NEEDS_ATTENTION;
        }
        if (snapshot.switchPreparing()) {
            return IndexDisplayStatus.CATCHING_UP;
        }
        if (snapshot.activeRun()) {
            return IndexDisplayStatus.REBUILDING;
        }
        if (snapshot.selected()) {
            return IndexDisplayStatus.PUBLISHED;
        }
        if (snapshot.dirty() || snapshot.buildState() != IndexBuildState.BUILT) {
            return IndexDisplayStatus.PENDING_REBUILD;
        }
        return IndexDisplayStatus.REBUILT;
    }

    /** 编辑配置：仅离线（非在线、非写入、非运行中）版本允许；原子递增修订。 */
    public static IndexVersionSnapshot editConfig(IndexVersionSnapshot snapshot,
                                                  String physicalNameFallback) {
        if (!snapshot.editable()) {
            throw new IllegalStateException(
                    "version " + snapshot.versionNumber() + " is online, write-enabled or busy;"
                            + " create the next auto-numbered version instead");
        }
        return new IndexVersionSnapshot(snapshot.versionNumber(),
                snapshot.physicalName() == null ? physicalNameFallback : snapshot.physicalName(),
                snapshot.configRevision() + 1,
                snapshot.builtConfigRevision(),
                snapshot.buildState(),
                snapshot.catchupStatus(),
                snapshot.writeEnabled(),
                snapshot.selected(),
                snapshot.pipelineSupported(),
                snapshot.deleted(),
                snapshot.needsAttentionReason(),
                snapshot.activeRun(),
                snapshot.switchPreparing());
    }

    /** 基线构建开始：置 BUILDING；configRevision 保持不变（构建期间禁止编辑）。 */
    public static IndexVersionSnapshot startBuild(IndexVersionSnapshot snapshot) {
        if (snapshot.selected() || snapshot.writeEnabled() || snapshot.activeRun()) {
            throw new IllegalStateException(
                    "version " + snapshot.versionNumber() + " cannot be rebuilt in place");
        }
        return new IndexVersionSnapshot(snapshot.versionNumber(), snapshot.physicalName(),
                snapshot.configRevision(), snapshot.builtConfigRevision(),
                IndexBuildState.BUILDING, snapshot.catchupStatus(), snapshot.writeEnabled(),
                snapshot.selected(), snapshot.pipelineSupported(), snapshot.deleted(),
                snapshot.needsAttentionReason(), true, snapshot.switchPreparing());
    }

    /**
     * 基线构建成功：仅当构建仍基于当前配置修订时才标记 BUILT 并更新
     * builtConfigRevision；构建期间配置被换（防御路径）时保持 dirty。
     * 新构建的数据尚未追平实时写入，catchup 置 BEHIND。
     */
    public static IndexVersionSnapshot completeBuild(IndexVersionSnapshot snapshot,
                                                     long builtRevision) {
        if (builtRevision != snapshot.configRevision()) {
            return new IndexVersionSnapshot(snapshot.versionNumber(), snapshot.physicalName(),
                    snapshot.configRevision(), snapshot.builtConfigRevision(),
                    snapshot.buildState(), IndexCatchupStatus.BEHIND, snapshot.writeEnabled(),
                    snapshot.selected(), snapshot.pipelineSupported(), snapshot.deleted(),
                    snapshot.needsAttentionReason(), snapshot.activeRun(),
                    snapshot.switchPreparing());
        }
        return new IndexVersionSnapshot(snapshot.versionNumber(), snapshot.physicalName(),
                snapshot.configRevision(), builtRevision, IndexBuildState.BUILT,
                IndexCatchupStatus.BEHIND, snapshot.writeEnabled(), snapshot.selected(),
                snapshot.pipelineSupported(), snapshot.deleted(),
                snapshot.needsAttentionReason(), false, snapshot.switchPreparing());
    }

    /** 基线构建失败：保持 dirty，可再次发起重建。 */
    public static IndexVersionSnapshot failBuild(IndexVersionSnapshot snapshot) {
        return new IndexVersionSnapshot(snapshot.versionNumber(), snapshot.physicalName(),
                snapshot.configRevision(), snapshot.builtConfigRevision(),
                IndexBuildState.FAILED, snapshot.catchupStatus(), snapshot.writeEnabled(),
                snapshot.selected(), snapshot.pipelineSupported(), snapshot.deleted(),
                snapshot.needsAttentionReason(), false, snapshot.switchPreparing());
    }

    /** 停用写入：退出写目标集合，ES 数据保留；不再保证可直接热切换。 */
    public static IndexVersionSnapshot disableWrites(IndexVersionSnapshot snapshot) {
        if (snapshot.selected()) {
            throw new IllegalStateException(
                    "version " + snapshot.versionNumber() + " is the read target;"
                            + " switch away before disabling writes");
        }
        return new IndexVersionSnapshot(snapshot.versionNumber(), snapshot.physicalName(),
                snapshot.configRevision(), snapshot.builtConfigRevision(),
                snapshot.buildState(), IndexCatchupStatus.BEHIND, false, false,
                snapshot.pipelineSupported(), snapshot.deleted(),
                snapshot.needsAttentionReason(), snapshot.activeRun(),
                snapshot.switchPreparing());
    }

    /** 重新启用：加入未来写入，但保持 BEHIND——必须补齐并重新校验后才能选择。 */
    public static IndexVersionSnapshot enableWrites(IndexVersionSnapshot snapshot) {
        if (snapshot.deleted()) {
            throw new IllegalStateException("deleted versions cannot be re-enabled");
        }
        return new IndexVersionSnapshot(snapshot.versionNumber(), snapshot.physicalName(),
                snapshot.configRevision(), snapshot.builtConfigRevision(),
                snapshot.buildState(), snapshot.catchupStatus(), true, snapshot.selected(),
                snapshot.pipelineSupported(), snapshot.deleted(),
                snapshot.needsAttentionReason(), snapshot.activeRun(),
                snapshot.switchPreparing());
    }

    /** 切换准备完成（追平 + 校验 + 原子别名切换成功）：当前别名目标。 */
    public static IndexVersionSnapshot publish(IndexVersionSnapshot snapshot) {
        return new IndexVersionSnapshot(snapshot.versionNumber(), snapshot.physicalName(),
                snapshot.configRevision(), snapshot.builtConfigRevision(),
                snapshot.buildState(), IndexCatchupStatus.CURRENT, true, true,
                snapshot.pipelineSupported(), snapshot.deleted(), null, false, false);
    }

    /** 别名切离后：仍是写目标（除非管理员停用），同步健康度独立展示。 */
    public static IndexVersionSnapshot unpublish(IndexVersionSnapshot snapshot) {
        return new IndexVersionSnapshot(snapshot.versionNumber(), snapshot.physicalName(),
                snapshot.configRevision(), snapshot.builtConfigRevision(),
                snapshot.buildState(), snapshot.catchupStatus(), snapshot.writeEnabled(),
                false, snapshot.pipelineSupported(), snapshot.deleted(),
                snapshot.needsAttentionReason(), snapshot.activeRun(),
                snapshot.switchPreparing());
    }

    /** 追平屏障收敛：切换准备期间的补齐完成。 */
    public static IndexVersionSnapshot caughtUp(IndexVersionSnapshot snapshot) {
        return new IndexVersionSnapshot(snapshot.versionNumber(), snapshot.physicalName(),
                snapshot.configRevision(), snapshot.builtConfigRevision(),
                snapshot.buildState(), IndexCatchupStatus.CURRENT, snapshot.writeEnabled(),
                snapshot.selected(), snapshot.pipelineSupported(), snapshot.deleted(),
                snapshot.needsAttentionReason(), snapshot.activeRun(),
                snapshot.switchPreparing());
    }

    /** 流水线受支持性变化：不受支持的在线/写入版本必须显著可见并阻止切换。 */
    public static IndexVersionSnapshot pipelineSupportChanged(IndexVersionSnapshot snapshot,
                                                              boolean supported) {
        return new IndexVersionSnapshot(snapshot.versionNumber(), snapshot.physicalName(),
                snapshot.configRevision(), snapshot.builtConfigRevision(),
                snapshot.buildState(), snapshot.catchupStatus(), snapshot.writeEnabled(),
                snapshot.selected(), supported, snapshot.deleted(),
                snapshot.needsAttentionReason(), snapshot.activeRun(),
                snapshot.switchPreparing());
    }

    /** 对账修复：清除或设置 NEEDS_ATTENTION 原因（脱敏文本）。 */
    public static IndexVersionSnapshot reconcile(IndexVersionSnapshot snapshot,
                                                 String needsAttentionReason) {
        return snapshot.withNeedsAttention(
                needsAttentionReason == null || needsAttentionReason.isBlank()
                        ? null : needsAttentionReason);
    }
}
