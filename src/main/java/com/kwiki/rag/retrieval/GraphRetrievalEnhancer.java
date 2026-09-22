package com.kwiki.rag.retrieval;

import com.kwiki.graph.CommunityRecallCoordinator;
import com.kwiki.graph.CommunitySearchHit;
import com.kwiki.graph.GraphEnhancementFallbackPolicy;
import com.kwiki.graph.GraphEvidenceFusion;
import com.kwiki.graph.GraphExpansionPort;
import com.kwiki.graph.GraphExpansionRequest;
import com.kwiki.graph.GraphExpansionResult;
import com.kwiki.graph.GraphKnowledgeBaseGateService;
import com.kwiki.graph.GraphRunOutboundGuard;
import com.kwiki.graph.GraphSeed;
import com.kwiki.graph.GraphSeedExtractor;
import com.kwiki.graph.GraphSelectionScope;
import com.kwiki.graph.GraphSnapshot;
import com.kwiki.graph.GraphSnapshotPin;
import com.kwiki.graph.GraphSourceChildResolverPort;
import com.kwiki.graph.GraphSourceCoverage;
import com.kwiki.graph.GraphSourceCoverageService;
import com.kwiki.graph.GraphSourceInventoryPort;
import com.kwiki.graph.GraphSnapshotManifestPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * retrieveChildren 阶段的确定性图增强：pin 快照 → 全来源门禁 → 社区双路
 * 召回 → ES entityIds 种子 → 回退策略 → 有界图扩展 → 来源解析与 RRF 融合
 * → 出站 epoch 复核。所有轮共享 run 级 1.5s 增强预算、边检查量与新增 CHILD
 * 额度，不随轮次或回调重试重置；被门禁拒绝的库不发起任何社区或图调用；
 * 图分支失败时保留 Chunk 证据继续原流程。DIRECT 路径不查图。
 */
public class GraphRetrievalEnhancer {

    public static final long ENHANCEMENT_TIME_BUDGET_MS = 1_500;
    public static final int MAX_NEW_CHILDREN_PER_RUN = GraphEvidenceFusion.MAX_NEW_CHILDREN;

    /** run 级共享预算：各轮累计，不重置。 */
    public static final class SharedBudget {
        long usedMs;
        int edgeChecks;
        int childrenAdded;

        public long usedMs() {
            return usedMs;
        }

        public int edgeChecks() {
            return edgeChecks;
        }

        public int childrenAdded() {
            return childrenAdded;
        }
    }

    public record Enhancement(List<ChildEvidence> mergedChildren,
                              List<CommunitySearchHit> communitiesUsed,
                              List<String> degradations,
                              boolean enhanced) {
        static Enhancement none(List<ChildEvidence> chunkChildren, String reason) {
            return new Enhancement(chunkChildren, List.of(), List.of(reason), false);
        }
    }

    /** pin 会话端口：固定各库配对并持有读取租约，结束时释放；测试可替换。 */
    public interface SnapshotPinner {
        Map<Long, Optional<GraphSnapshotPin>> pinAll(java.util.Collection<Long> kbIds);

        void release(Iterable<GraphSnapshotPin> pins);
    }

    private final SnapshotPinner pinner;
    private final GraphSourceInventoryPort inventory;
    private final GraphSnapshotManifestPort manifests;
    private final GraphSourceChildResolverPort childResolver;
    private final CommunityRecallCoordinator communityRecall;
    private final GraphExpansionPort expansion;
    private final int maxHops;
    private final int maxEdgesPerNode;
    private final int maxEdgesTotal;
    private final int maxEntities;
    private final int maxRelations;

    public GraphRetrievalEnhancer(SnapshotPinner pinner,
                                  GraphSourceInventoryPort inventory,
                                  GraphSnapshotManifestPort manifests,
                                  GraphSourceChildResolverPort childResolver,
                                  CommunityRecallCoordinator communityRecall,
                                  GraphExpansionPort expansion,
                                  int maxHops, int maxEdgesPerNode, int maxEdgesTotal,
                                  int maxEntities, int maxRelations) {
        this.pinner = pinner;
        this.inventory = inventory;
        this.manifests = manifests;
        this.childResolver = childResolver;
        this.communityRecall = communityRecall;
        this.expansion = expansion;
        this.maxHops = maxHops;
        this.maxEdgesPerNode = maxEdgesPerNode;
        this.maxEdgesTotal = maxEdgesTotal;
        this.maxEntities = maxEntities;
        this.maxRelations = maxRelations;
    }

    /**
     * @param chunkChildren 本轮 Chunk 检索融合结果（保持原排名与来源身份）
     * @param directAnswer  DIRECT 路径不查图
     */
    public Enhancement enhance(long userId, boolean superuser,
                               List<ChildEvidence> chunkChildren,
                               String query,
                               GraphSelectionScope selectedScope,
                               Map<String, float[]> embeddingCache,
                               SharedBudget budget,
                               boolean directAnswer) {
        List<ChildEvidence> chunkList = chunkChildren == null ? List.of() : chunkChildren;
        if (directAnswer) {
            return Enhancement.none(chunkList, "direct-answer-no-graph");
        }
        if (budget.usedMs >= ENHANCEMENT_TIME_BUDGET_MS) {
            return Enhancement.none(chunkList, "graph-budget-exhausted");
        }
        if (budget.childrenAdded >= MAX_NEW_CHILDREN_PER_RUN) {
            return Enhancement.none(chunkList, "graph-children-quota-exhausted");
        }
        Map<Long, Long> kbIds = new LinkedHashMap<>();
        for (ChildEvidence child : chunkList) {
            kbIds.putIfAbsent(child.kbId(), child.kbId());
        }
        if (kbIds.isEmpty()) {
            return Enhancement.none(chunkList, "graph-no-eligible-knowledge-base");
        }
        long startedAt = System.nanoTime();

        Map<Long, Optional<GraphSnapshotPin>> pinned = pinner.pinAll(kbIds.keySet());
        List<GraphSnapshotPin> heldPins = new ArrayList<>();
        for (Optional<GraphSnapshotPin> pin : pinned.values()) {
            pin.ifPresent(heldPins::add);
        }
        try {
            return doEnhance(userId, superuser, chunkList, query, selectedScope,
                    embeddingCache, budget, pinned, startedAt);
        } finally {
            pinner.release(heldPins);
        }
    }

    private Enhancement doEnhance(long userId, boolean superuser,
                                  List<ChildEvidence> chunkChildren,
                                  String query,
                                  GraphSelectionScope selectedScope,
                                  Map<String, float[]> embeddingCache,
                                  SharedBudget budget,
                                  Map<Long, Optional<GraphSnapshotPin>> pinned,
                                  long startedAt) {
        List<String> degradations = new ArrayList<>();
        Map<Long, GraphSnapshot> pinnedByKb = new LinkedHashMap<>();
        for (Map.Entry<Long, Optional<GraphSnapshotPin>> entry : pinned.entrySet()) {
            if (entry.getValue().isPresent()) {
                pinnedByKb.put(entry.getKey(), entry.getValue().get().snapshot());
            } else {
                degradations.add("graph-snapshot-unavailable");
            }
        }
        if (pinnedByKb.isEmpty()) {
            return finish(chunkChildren, List.of(), degradations, false, budget, startedAt);
        }

        // 全来源门禁：不通过的库不进入任何社区或图步骤；快照配对自带
        // 本 run 固定的 Chunk 版本，配对不匹配的库在门禁处关闭。
        GraphSourceCoverageService coverageService = new GraphSourceCoverageService(inventory);
        var gateInputs =
                new LinkedHashMap<Long, GraphKnowledgeBaseGateService.GateInput>();
        for (Map.Entry<Long, GraphSnapshot> entry : pinnedByKb.entrySet()) {
            GraphSnapshot snapshot = entry.getValue();
            GraphSourceCoverage coverage = coverageService.prove(userId, superuser,
                    entry.getKey(), snapshot,
                    manifests.manifestResources(snapshot.snapshotId()), selectedScope);
            gateInputs.put(entry.getKey(),
                    new GraphKnowledgeBaseGateService.GateInput(coverage, snapshot));
        }
        long pinnedChunkVersion = pinnedByKb.values().iterator().next().chunkIndexVersion();
        Map<Long, GraphSnapshot> eligible =
                new GraphKnowledgeBaseGateService().eligible(gateInputs,
                        (int) pinnedChunkVersion);
        for (Long kbId : pinnedByKb.keySet()) {
            if (!eligible.containsKey(kbId)) {
                degradations.add("graph-gate-closed");
            }
        }
        if (eligible.isEmpty()) {
            return finish(chunkChildren, List.of(), degradations, false, budget, startedAt);
        }

        // 社区双路召回（仅通过门禁的库）。
        CommunityRecallCoordinator.RecallOutcome communities =
                communityRecall.recall(query, eligible, embeddingCache);
        if (communities.anyDegraded()) {
            degradations.add("community-search-degraded");
        }
        Map<Long, List<CommunitySearchHit>> hitsByKb = new LinkedHashMap<>();
        for (CommunityRecallCoordinator.KbRecall recall : communities.perKnowledgeBase()) {
            if (eligible.containsKey(recall.kbId()) && !recall.communities().isEmpty()) {
                hitsByKb.put(recall.kbId(), recall.communities());
            }
        }

        // 每库回退策略与有界扩展。
        List<GraphEvidenceFusion.GraphRelationEvidence> graphEvidence = new ArrayList<>();
        for (Map.Entry<Long, GraphSnapshot> entry : eligible.entrySet()) {
            long kbId = entry.getKey();
            GraphSnapshot snapshot = entry.getValue();
            List<GraphSeed> seeds = GraphSeedExtractor.extract(
                    chunkHitsOf(chunkChildren, kbId), snapshot.entityLinkingVersion());
            List<CommunitySearchHit> kbCommunities = hitsByKb.getOrDefault(kbId, List.of());
            boolean serviceFailed = communities.perKnowledgeBase().stream()
                    .anyMatch(recall -> recall.kbId() == kbId && recall.degraded());
            GraphEnhancementFallbackPolicy.Mode mode = GraphEnhancementFallbackPolicy.resolve(
                    true, !seeds.isEmpty(), !kbCommunities.isEmpty(), serviceFailed);
            if (mode == GraphEnhancementFallbackPolicy.Mode.OFF
                    || mode == GraphEnhancementFallbackPolicy.Mode.SKIP) {
                continue;
            }
            if (mode == GraphEnhancementFallbackPolicy.Mode.REPRESENTATIVE_ENTITY) {
                // 只有社区命中且无 Chunk 种子：先加载并验证代表实体的来源原文，
                // 没有可用原文则跳过增强，不能只用摘要回答。
                seeds = verifiedRepresentativeSeeds(snapshot, kbCommunities.get(0));
                if (seeds.isEmpty()) {
                    degradations.add("representative-source-unavailable");
                    continue;
                }
            }
            try {
                GraphExpansionResult expanded = expansion.expand(new GraphExpansionRequest(
                        kbId, snapshot.graphVersion(), communityScope(mode, kbCommunities),
                        seeds, maxHops, maxEdgesPerNode, maxEdgesTotal, maxEntities,
                        maxRelations));
                budget.edgeChecks += expanded.edgeChecks();
                graphEvidence.addAll(toEvidence(kbId, expanded, seeds));
            } catch (RuntimeException failure) {
                degradations.add("graph-expansion-failed");
            }
        }
        if (graphEvidence.isEmpty()) {
            return finish(chunkChildren, List.of(), degradations, false, budget, startedAt);
        }

        // 图关系来源 → 当前有效 CHILD，再与 Chunk 融合列表 RRF 合并。
        List<GraphEvidenceFusion.GraphRelationEvidence> resolvable =
                resolveSources(graphEvidence, pinnedByKb);
        int remainingChildren = MAX_NEW_CHILDREN_PER_RUN - budget.childrenAdded;
        GraphEvidenceFusion.FusionOutcome fused = GraphEvidenceFusion.fuse(
                chunkChildren, resolvable,
                Math.max(chunkChildren.size(), remainingChildren), remainingChildren);

        // 出站 epoch 复核：失效即丢弃全部图新增，保留 Chunk 证据继续原流程。
        boolean epochInvalid = pinnedByKb.entrySet().stream().anyMatch(entry -> {
            long[] current = inventory.currentEpochs(entry.getKey());
            return GraphRunOutboundGuard.check(entry.getValue(), current[0], current[1])
                    != GraphRunOutboundGuard.Verdict.OK;
        });
        if (epochInvalid) {
            degradations.add("graph-epoch-invalidated");
            return finish(chunkChildren, List.of(), degradations, false, budget, startedAt);
        }
        budget.childrenAdded += fused.newChildren().size();
        return finish(fused.mergedChildren(),
                hitsByKb.values().stream().flatMap(List::stream).toList(),
                degradations, !fused.newChildren().isEmpty(), budget, startedAt);
    }

    /** seed-only 回退使用独立社区范围标记；社区命中时限定在 Top 社区内。 */
    private static String communityScope(GraphEnhancementFallbackPolicy.Mode mode,
                                         List<CommunitySearchHit> communities) {
        return mode == GraphEnhancementFallbackPolicy.Mode.SEED_ONLY
                ? GraphExpansionRequest.UNSCOPED_COMMUNITY
                : communities.get(0).communityId();
    }

    /** 代表实体路径：先验证 Top 社区摘要来源可解析为当前有效 CHILD。 */
    private List<GraphSeed> verifiedRepresentativeSeeds(GraphSnapshot snapshot,
                                                        CommunitySearchHit community) {
        String verifiedSourceChunkId = null;
        for (com.kwiki.graph.GraphSourceRef ref : community.sourceRefs()) {
            if (childResolver.resolve(snapshot.chunkPhysicalIndex(), ref.sourceChunkId(),
                    snapshot.entityLinkingVersion(), snapshot.kbId()).isPresent()) {
                verifiedSourceChunkId = ref.sourceChunkId();
                break;
            }
        }
        if (verifiedSourceChunkId == null) {
            return List.of();
        }
        List<GraphSeed> seeds = new ArrayList<>();
        for (String entityId : community.representativeEntityIds()) {
            if (entityId != null && !entityId.isBlank()) {
                seeds.add(new GraphSeed(entityId, verifiedSourceChunkId));
            }
        }
        return seeds;
    }

    private Enhancement finish(List<ChildEvidence> merged, List<CommunitySearchHit> communities,
                               List<String> degradations, boolean enhanced,
                               SharedBudget budget, long startedAt) {
        budget.usedMs += (System.nanoTime() - startedAt) / 1_000_000;
        return new Enhancement(merged, communities, List.copyOf(degradations), enhanced);
    }

    private List<GraphEvidenceFusion.GraphRelationEvidence> resolveSources(
            List<GraphEvidenceFusion.GraphRelationEvidence> evidence,
            Map<Long, GraphSnapshot> pinnedByKb) {
        List<GraphEvidenceFusion.GraphRelationEvidence> resolvable = new ArrayList<>();
        for (GraphEvidenceFusion.GraphRelationEvidence relation : evidence) {
            GraphSnapshot snapshot = pinnedByKb.get(relation.kbId());
            List<ChildEvidence> children = new ArrayList<>();
            for (String sourceChunkId : relation.sourceChunkIds()) {
                Optional<ChunkHit> hit = childResolver.resolve(
                        snapshot.chunkPhysicalIndex(), sourceChunkId,
                        snapshot.entityLinkingVersion(), relation.kbId());
                hit.ifPresent(value -> children.add(
                        ChildEvidence.fromHit(value, null, null, 0)));
            }
            if (!children.isEmpty()) {
                resolvable.add(relation.withResolvedChildren(children));
            }
        }
        return resolvable;
    }

    private static List<GraphEvidenceFusion.GraphRelationEvidence> toEvidence(
            long kbId, GraphExpansionResult expanded, List<GraphSeed> seeds) {
        Map<String, Integer> seedOrder = new LinkedHashMap<>();
        for (int i = 0; i < seeds.size(); i++) {
            seedOrder.put(seeds.get(i).entityId(), i + 1);
        }
        List<GraphEvidenceFusion.GraphRelationEvidence> evidence = new ArrayList<>();
        for (var relation : expanded.relations()) {
            List<String> sourceChunkIds = relation.sourceRefs().stream()
                    .map(ref -> ref.sourceChunkId()).distinct().sorted().toList();
            evidence.add(new GraphEvidenceFusion.GraphRelationEvidence(
                    kbId, kbId + ":" + relation.relationId(),
                    seedOrder.getOrDefault(relation.sourceEntityId(), 1),
                    1, relation.sourceRefs().size(), sourceChunkIds, List.of(),
                    relation.predicate().name() + ":" + relation.targetEntityId()));
        }
        return evidence;
    }

    private static List<ChunkHit> chunkHitsOf(List<ChildEvidence> children, long kbId) {
        List<ChunkHit> hits = new ArrayList<>();
        for (ChildEvidence child : children) {
            if (child.kbId() != kbId) {
                continue;
            }
            hits.add(new ChunkHit(child.chunkKey(), child.parentChunkKey(), child.kbId(),
                    child.resourceType(), child.resourceId(), child.revisionId(),
                    child.headingPath(), child.charStart(), child.charEnd(), child.content(),
                    List.of(), child.sourceChunkId(), child.entityIds(),
                    child.entityLinkingVersion(), child.entityLinkingStatus()));
        }
        return hits;
    }
}
