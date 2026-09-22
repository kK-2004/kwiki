package com.kwiki.graph;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 10.x 全来源权限门禁验证：PRIVATE/指定成员/库外共享、只选一页、旧快照外
 * 新增私有文档、多库混合权限及流式撤权；被门禁拒绝的库不得进入任何图步骤。
 */
class GraphFullSourceGateTest {

    private static final long KB = 7;
    private static final long USER = 42;

    private static GraphSnapshot snapshot(long contentEpoch, long securityEpoch) {
        return new GraphSnapshot(91, KB, 42, 3, 5, "kwiki-chunks-v3",
                "kwiki-communities-v5-kb7", "entity-linking-v1",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                contentEpoch, securityEpoch);
    }

    /** 可编程来源清单端口：记录读取调用，供零调用断言使用。 */
    private static final class FakeInventory implements GraphSourceInventoryPort {
        List<GraphResourceId> currentSources;
        Set<GraphResourceId> readable;
        long contentEpoch = 5;
        long securityEpoch = 2;
        int canReadCalls;

        @Override
        public List<GraphResourceId> listCurrentSources(long kbId) {
            return currentSources;
        }

        @Override
        public boolean canRead(long userId, boolean superuser, GraphResourceId resource) {
            canReadCalls++;
            return readable.contains(resource);
        }

        @Override
        public long[] currentEpochs(long kbId) {
            return new long[]{contentEpoch, securityEpoch};
        }
    }

    @Test
    void fullSourceVisibilityWithEntireKbSelectionPassesTheGate() {
        FakeInventory inventory = new FakeInventory();
        inventory.currentSources = List.of(GraphResourceId.page(1), GraphResourceId.page(2));
        inventory.readable = Set.of(GraphResourceId.page(1), GraphResourceId.page(2));
        GraphSourceCoverageService service = new GraphSourceCoverageService(inventory);

        GraphSourceCoverage coverage = service.prove(USER, false, KB, snapshot(5, 2),
                List.of(GraphResourceId.page(1), GraphResourceId.page(2)),
                GraphSelectionScope.all());
        GraphAuthorizationGate.Decision decision = new GraphAuthorizationGate()
                .check(coverage, snapshot(5, 2), 3);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void privateOrSelectedMemberSourcesFailTheProofEvenForKbMembers() {
        FakeInventory inventory = new FakeInventory();
        inventory.currentSources = List.of(GraphResourceId.page(1), GraphResourceId.page(2));
        inventory.readable = Set.of(GraphResourceId.page(1));
        GraphSourceCoverageService service = new GraphSourceCoverageService(inventory);

        GraphSourceCoverage coverage = service.prove(USER, false, KB, snapshot(5, 2),
                List.of(GraphResourceId.page(1), GraphResourceId.page(2)),
                GraphSelectionScope.all());

        assertThat(coverage.complete()).isFalse();
        assertThat(new GraphAuthorizationGate().check(coverage, snapshot(5, 2), 3)
                .reason()).isEqualTo("source-coverage-incomplete");
    }

    @Test
    void selectingASinglePageClosesCommunityPathEvenWithFullPermission() {
        FakeInventory inventory = new FakeInventory();
        inventory.currentSources = List.of(GraphResourceId.page(1), GraphResourceId.page(2));
        inventory.readable = Set.of(GraphResourceId.page(1), GraphResourceId.page(2));
        GraphSourceCoverageService service = new GraphSourceCoverageService(inventory);

        GraphSourceCoverage coverage = service.prove(USER, false, KB, snapshot(5, 2),
                List.of(GraphResourceId.page(1), GraphResourceId.page(2)),
                GraphSelectionScope.of(GraphResourceId.page(1)));

        assertThat(coverage.complete()).isFalse();
        assertThat(new GraphAuthorizationGate().check(coverage, snapshot(5, 2), 3)
                .reason()).isEqualTo("source-coverage-incomplete");
    }

    @Test
    void privateDocumentAddedAfterSnapshotCapturesClosesTheGate() {
        FakeInventory inventory = new FakeInventory();
        inventory.currentSources = List.of(GraphResourceId.page(1), GraphResourceId.page(2),
                GraphResourceId.page(9));
        inventory.readable = Set.of(GraphResourceId.page(1), GraphResourceId.page(2));
        GraphSourceCoverageService service = new GraphSourceCoverageService(inventory);

        GraphSourceCoverage coverage = service.prove(USER, false, KB, snapshot(5, 2),
                List.of(GraphResourceId.page(1), GraphResourceId.page(2)),
                GraphSelectionScope.all());

        // 期望集合取并集：新增的 page(9) 不可见即关闭，即使快照来源全部可读。
        assertThat(coverage.expectedSourceCount()).isEqualTo(3);
        assertThat(coverage.visibleSourceCount()).isEqualTo(2);
        assertThat(coverage.complete()).isFalse();
    }

    @Test
    void mixedPermissionKnowledgeBasesAreJudgedIndependently() {
        GraphSnapshot kb7Snapshot = snapshot(5, 2);
        GraphSnapshot kb8Snapshot = new GraphSnapshot(97, 8, 50, 3, 6, "kwiki-chunks-v3",
                "kwiki-communities-v6-kb8", "entity-linking-v1",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                5, 2);
        FakeInventory inventory = new FakeInventory();
        inventory.currentSources = List.of(GraphResourceId.page(1), GraphResourceId.page(2));
        inventory.readable = Set.of(GraphResourceId.page(1), GraphResourceId.page(2));
        GraphSourceCoverage kb7Coverage = new GraphSourceCoverage(2, 2, 2, 5, 2, 5, 2);
        GraphSourceCoverage kb8Coverage = new GraphSourceCoverage(2, 1, 2, 5, 2, 5, 2);
        GraphKnowledgeBaseGateService gate = new GraphKnowledgeBaseGateService();

        LinkedHashMap<Long, GraphKnowledgeBaseGateService.GateInput> inputs = new LinkedHashMap<>();
        inputs.put(7L, new GraphKnowledgeBaseGateService.GateInput(kb7Coverage, kb7Snapshot));
        inputs.put(8L, new GraphKnowledgeBaseGateService.GateInput(kb8Coverage, kb8Snapshot));

        Map<Long, GraphAuthorizationGate.Decision> decisions = gate.evaluate(inputs, 3);
        Map<Long, GraphSnapshot> eligible = gate.eligible(inputs, 3);

        assertThat(decisions.get(7L).allowed()).isTrue();
        assertThat(decisions.get(8L).allowed()).isFalse();
        assertThat(eligible).containsOnlyKeys(7L);
    }

    @Test
    void gateInputFailureClosesOnlyThatKnowledgeBase() {
        GraphSnapshot kb7Snapshot = snapshot(5, 2);
        GraphKnowledgeBaseGateService gate = new GraphKnowledgeBaseGateService();

        LinkedHashMap<Long, GraphKnowledgeBaseGateService.GateInput> inputs = new LinkedHashMap<>();
        inputs.put(7L, new GraphKnowledgeBaseGateService.GateInput(null, kb7Snapshot));
        inputs.put(8L, new GraphKnowledgeBaseGateService.GateInput(
                new GraphSourceCoverage(1, 1, 1, 5, 2, 5, 2), snapshot(5, 2)));

        Map<Long, GraphAuthorizationGate.Decision> decisions = gate.evaluate(inputs, 3);

        assertThat(decisions.get(7L).reason()).isEqualTo("graph-snapshot-unavailable");
        assertThat(decisions.get(8L).allowed()).isTrue();
    }

    @Test
    void streamingRevocationInvalidatesOutboundGraphEpochsAndMustAbortNotDegrade() {
        GraphSnapshot pinned = snapshot(5, 2);

        assertThat(GraphRunOutboundGuard.check(pinned, 5, 2))
                .isEqualTo(GraphRunOutboundGuard.Verdict.OK);
        assertThat(GraphRunOutboundGuard.check(pinned, 6, 2))
                .isEqualTo(GraphRunOutboundGuard.Verdict.SNAPSHOT_EPOCH_INVALID);
        assertThat(GraphRunOutboundGuard.check(pinned, 5, 3))
                .isEqualTo(GraphRunOutboundGuard.Verdict.SNAPSHOT_EPOCH_INVALID);
        assertThat(GraphRunOutboundGuard.checkAll(Map.of(7L, pinned),
                Map.of(7L, new long[]{6, 2})))
                .isEqualTo(GraphRunOutboundGuard.Verdict.SNAPSHOT_EPOCH_INVALID);
        assertThat(GraphRunOutboundGuard.checkAll(Map.of(7L, pinned),
                Map.of(7L, new long[]{5, 3})))
                .isEqualTo(GraphRunOutboundGuard.Verdict.SNAPSHOT_EPOCH_INVALID);
        assertThat(GraphRunOutboundGuard.checkAll(Map.of(), Map.of()))
                .isEqualTo(GraphRunOutboundGuard.Verdict.OK);
    }

    @Test
    void selectionScopeAndResourceIdValidateTheirInputs() {
        assertThatThrownBy(() -> GraphSelectionScope.of())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphResourceId(" ", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphSourceCoverageService(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(GraphResourceId.page(3)).isEqualTo(new GraphResourceId("page", 3));
    }
}
