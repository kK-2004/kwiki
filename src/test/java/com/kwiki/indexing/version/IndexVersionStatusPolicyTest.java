package com.kwiki.indexing.version;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 版本状态机契约：dirty 派生、构建/存量迁移独立、展示状态优先级、
 * 编辑限制与 NEEDS_ATTENTION 对账。
 */
class IndexVersionStatusPolicyTest {

    private static IndexVersionSnapshot snapshot(
            long configRevision, Long builtRevision, IndexBuildState buildState,
            IndexCatchupStatus catchup, boolean writeEnabled, boolean selected,
            boolean supported, boolean activeRun, String attention, boolean deleted) {
        return new IndexVersionSnapshot(2, "kwiki-chunks-v2", configRevision, builtRevision,
                buildState, catchup, writeEnabled, selected, supported, deleted, attention,
                activeRun);
    }

    @Test
    void dirtyIsDerivedFromRevisionsNeverStored() {
        assertThat(snapshot(3, null, IndexBuildState.NEW, IndexCatchupStatus.BEHIND,
                false, false, true, false, null, false).dirty()).isTrue();
        assertThat(snapshot(3, 3L, IndexBuildState.BUILT, IndexCatchupStatus.BEHIND,
                false, false, true, false, null, false).dirty()).isFalse();
        assertThat(snapshot(4, 3L, IndexBuildState.BUILT, IndexCatchupStatus.BEHIND,
                false, false, true, false, null, false).dirty()).isTrue();
    }

    @Test
    void displayStatusFollowsTheDocumentedPriority() {
        assertThat(IndexVersionStatusPolicy.displayStatus(snapshot(3, null,
                IndexBuildState.NEW, IndexCatchupStatus.BEHIND, false, false, true,
                false, "alias mismatch", false)))
                .isEqualTo(IndexDisplayStatus.NEEDS_ATTENTION);
        assertThat(IndexVersionStatusPolicy.displayStatus(snapshot(1, null,
                IndexBuildState.BUILDING, IndexCatchupStatus.BEHIND, true, false, true,
                true, null, false)))
                .isEqualTo(IndexDisplayStatus.MIGRATING);
        assertThat(IndexVersionStatusPolicy.displayStatus(snapshot(2, 2L,
                IndexBuildState.BUILT, IndexCatchupStatus.CURRENT, true, true, true,
                false, null, false)))
                .isEqualTo(IndexDisplayStatus.PUBLISHED);
        assertThat(IndexVersionStatusPolicy.displayStatus(snapshot(1, null,
                IndexBuildState.NEW, IndexCatchupStatus.BEHIND, false, false, true,
                false, null, false)))
                .isEqualTo(IndexDisplayStatus.PENDING_MIGRATION);
        assertThat(IndexVersionStatusPolicy.displayStatus(snapshot(2, 2L,
                IndexBuildState.BUILT, IndexCatchupStatus.BEHIND, false, false, true,
                false, null, false)))
                .isEqualTo(IndexDisplayStatus.PENDING_MIGRATION);
        assertThat(IndexVersionStatusPolicy.displayStatus(snapshot(2, 2L,
                IndexBuildState.BUILT, IndexCatchupStatus.CURRENT, true, false, true,
                false, null, false)))
                .isEqualTo(IndexDisplayStatus.MIGRATED);
    }

    @Test
    void builtButBehindVersionDisplaysPendingMigration() {
        var rebuilt = snapshot(2, 2L, IndexBuildState.BUILT, IndexCatchupStatus.BEHIND,
                false, false, true, false, null, false);
        assertThat(IndexVersionStatusPolicy.displayStatus(rebuilt))
                .isEqualTo(IndexDisplayStatus.PENDING_MIGRATION);
    }

    @Test
    void editingIsRestrictedToOfflineIdleVersions() {
        var offline = snapshot(2, 2L, IndexBuildState.BUILT, IndexCatchupStatus.BEHIND,
                false, false, true, false, null, false);
        assertThat(offline.editable()).isTrue();
        assertThat(IndexVersionStatusPolicy.editConfig(offline, null).configRevision())
                .isEqualTo(3);

        assertThat(snapshot(2, 2L, IndexBuildState.BUILT, IndexCatchupStatus.CURRENT,
                true, true, true, false, null, false).editable()).isFalse();
        assertThat(snapshot(2, 2L, IndexBuildState.BUILT, IndexCatchupStatus.CURRENT,
                true, false, true, false, null, false).editable()).isFalse();
        assertThat(snapshot(2, null, IndexBuildState.BUILDING, IndexCatchupStatus.BEHIND,
                false, false, true, true, null, false).editable()).isFalse();
        assertThat(snapshot(2, 2L, IndexBuildState.BUILT, IndexCatchupStatus.BEHIND,
                false, false, true, false, null, true).editable()).isFalse();
    }

    @Test
    void editingABuiltOfflineVersionKeepsTheNumberAndMarksItDirtyAgain() {
        var built = snapshot(2, 2L, IndexBuildState.BUILT, IndexCatchupStatus.BEHIND,
                false, false, true, false, null, false);
        var edited = IndexVersionStatusPolicy.editConfig(built, null);

        assertThat(edited.versionNumber()).isEqualTo(2);
        assertThat(edited.dirty()).isTrue();
        assertThat(edited.builtConfigRevision()).isEqualTo(2L);
    }

    @Test
    void editingAnOnlineVersionIsRejectedWithAnActionableMessage() {
        var online = snapshot(1, 1L, IndexBuildState.BUILT, IndexCatchupStatus.CURRENT,
                true, true, true, false, null, false);
        assertThatThrownBy(() -> IndexVersionStatusPolicy.editConfig(online, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("create the next auto-numbered version");
    }

    @Test
    void buildLifecycleTransitions() {
        var fresh = snapshot(1, null, IndexBuildState.NEW, IndexCatchupStatus.BEHIND,
                false, false, true, false, null, false);
        var building = IndexVersionStatusPolicy.startBuild(fresh);
        assertThat(building.buildState()).isEqualTo(IndexBuildState.BUILDING);
        assertThat(building.activeRun()).isTrue();

        var built = IndexVersionStatusPolicy.completeBuild(building, 1);
        assertThat(built.buildState()).isEqualTo(IndexBuildState.BUILT);
        assertThat(built.builtConfigRevision()).isEqualTo(1L);
        assertThat(built.dirty()).isFalse();
        assertThat(built.activeRun()).isFalse();
        assertThat(built.catchupStatus()).isEqualTo(IndexCatchupStatus.BEHIND);

        var failed = IndexVersionStatusPolicy.failBuild(building);
        assertThat(failed.buildState()).isEqualTo(IndexBuildState.FAILED);
        assertThat(failed.dirty()).isTrue();
        assertThat(failed.activeRun()).isFalse();
    }

    @Test
    void completingABuildAgainstAChangedRevisionMustNotMarkItBuilt() {
        // 防御路径：构建期间配置修订变化（理论上被编辑限制阻止）。
        var building = snapshot(4, 2L, IndexBuildState.BUILDING, IndexCatchupStatus.BEHIND,
                false, false, true, true, null, false);
        var completed = IndexVersionStatusPolicy.completeBuild(building, 3);

        assertThat(completed.builtConfigRevision()).isEqualTo(2L);
        assertThat(completed.dirty()).isTrue();
    }

    @Test
    void rebuildingAnOnlineVersionInPlaceIsRejected() {
        var online = snapshot(1, 1L, IndexBuildState.BUILT, IndexCatchupStatus.CURRENT,
                true, true, true, false, null, false);
        assertThatThrownBy(() -> IndexVersionStatusPolicy.startBuild(online))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be rebuilt in place");
    }

    @Test
    void publishSelectsAndClearsAttentionUnpublishKeepsWrites() {
        var candidate = snapshot(2, 2L, IndexBuildState.BUILT, IndexCatchupStatus.CURRENT,
                true, false, true, false, "stale marker", false);
        var published = IndexVersionStatusPolicy.publish(candidate);

        assertThat(published.selected()).isTrue();
        assertThat(published.writeEnabled()).isTrue();
        assertThat(published.catchupStatus()).isEqualTo(IndexCatchupStatus.CURRENT);
        assertThat(published.needsAttentionReason()).isNull();

        var unpublished = IndexVersionStatusPolicy.unpublish(published);
        assertThat(unpublished.selected()).isFalse();
        assertThat(unpublished.writeEnabled()).isTrue();
    }

    @Test
    void unsupportedPipelineBlocksSelectionEvenWhenCaughtUp() {
        var caughtUp = snapshot(2, 2L, IndexBuildState.BUILT, IndexCatchupStatus.CURRENT,
                true, false, true, false, null, false);
        var unsupported = IndexVersionStatusPolicy.pipelineSupportChanged(caughtUp, false);

        assertThat(unsupported.selectable()).isFalse();
        assertThat(caughtUp.selectable()).isTrue();
    }

    @Test
    void needsAttentionIsSetAndClearedByReconciliation() {
        var healthy = snapshot(1, 1L, IndexBuildState.BUILT, IndexCatchupStatus.CURRENT,
                true, true, true, false, null, false);
        var mismatch = IndexVersionStatusPolicy.reconcile(healthy,
                "alias target does not match persisted selection");

        assertThat(mismatch.needsAttentionReason()).isNotNull();
        assertThat(IndexVersionStatusPolicy.displayStatus(mismatch))
                .isEqualTo(IndexDisplayStatus.NEEDS_ATTENTION);

        var repaired = IndexVersionStatusPolicy.reconcile(mismatch, "  ");
        assertThat(repaired.needsAttentionReason()).isNull();
        assertThat(IndexVersionStatusPolicy.displayStatus(repaired))
                .isEqualTo(IndexDisplayStatus.PUBLISHED);
    }

    @Test
    void dirtyOrBehindOrUnsupportedVersionsAreNotSelectable() {
        assertThat(snapshot(3, 2L, IndexBuildState.BUILT, IndexCatchupStatus.CURRENT,
                true, false, true, false, null, false).selectable()).isFalse();
        assertThat(snapshot(2, 2L, IndexBuildState.BUILT, IndexCatchupStatus.BEHIND,
                true, false, true, false, null, false).selectable()).isFalse();
        assertThat(snapshot(2, 2L, IndexBuildState.BUILDING, IndexCatchupStatus.CURRENT,
                true, false, true, false, null, false).selectable()).isFalse();
        assertThat(snapshot(2, 2L, IndexBuildState.BUILT, IndexCatchupStatus.CURRENT,
                true, false, true, false, "marker", false).selectable()).isFalse();
        assertThat(snapshot(2, 2L, IndexBuildState.BUILT, IndexCatchupStatus.CURRENT,
                true, false, true, false, null, true).selectable()).isFalse();
    }

    @Test
    void 写入关闭时不能开始存量迁移() {
        assertThatThrownBy(() -> IndexVersionStatusPolicy.startMigration(snapshot(1, null,
                IndexBuildState.NEW, IndexCatchupStatus.BEHIND, false, false, true,
                false, null, false))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 开始存量迁移_进入构建中且标记活动() {
        IndexVersionSnapshot started = IndexVersionStatusPolicy.startMigration(snapshot(1, null,
                IndexBuildState.NEW, IndexCatchupStatus.BEHIND, true, false, true,
                false, null, false));
        assertThat(started.buildState()).isEqualTo(IndexBuildState.BUILDING);
        assertThat(started.activeRun()).isTrue();
    }

    @Test
    void 存量迁移完成_已构建且追平() {
        IndexVersionSnapshot done = IndexVersionStatusPolicy.completeMigration(snapshot(1, null,
                IndexBuildState.BUILDING, IndexCatchupStatus.BEHIND, true, false, true,
                true, null, false), 1);
        assertThat(done.buildState()).isEqualTo(IndexBuildState.BUILT);
        assertThat(done.builtConfigRevision()).isEqualTo(1L);
        assertThat(done.catchupStatus()).isEqualTo(IndexCatchupStatus.CURRENT);
        assertThat(done.activeRun()).isFalse();
    }

    @Test
    void 存量迁移完成时写入已关闭_保持落后() {
        IndexVersionSnapshot done = IndexVersionStatusPolicy.completeMigration(snapshot(1, null,
                IndexBuildState.BUILDING, IndexCatchupStatus.BEHIND, false, false, true,
                true, null, false), 1);
        assertThat(done.catchupStatus()).isEqualTo(IndexCatchupStatus.BEHIND);
        assertThat(done.buildState()).isEqualTo(IndexBuildState.FAILED);
    }
}
