package com.kwiki.rag.retrieval;

import com.kwiki.graph.CommunityRecallCoordinator;
import com.kwiki.graph.CommunitySearchHit;
import com.kwiki.graph.GraphAuthorizationGate;
import com.kwiki.graph.GraphEnhancementFallbackPolicy;
import com.kwiki.graph.GraphEvidenceFusion;
import com.kwiki.graph.GraphExpansionPort;
import com.kwiki.graph.GraphExpansionRequest;
import com.kwiki.graph.GraphExpansionResult;
import com.kwiki.graph.GraphKnowledgeBaseGateService;
import com.kwiki.graph.GraphResourceId;
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
 * retrieveChildren 阶段的确定性图增强：pin 快照 → 三档门禁（FULL/FACTS_ONLY/OFF）
 * → 社区双路召回（仅 FULL）→ ES entityIds 种子 → 回退策略（FACTS_ONLY 仅 seed-only）
 * → 有界图扩展 → 来源解析与业务库二次校验 → RRF 融合 → 出站 epoch 复核（仅 FULL）。
 * 所有轮共享 run 级 1.5s 增强预算、边检查量与新增 CHILD 额度，不随轮次或回调重试重置；
 * OFF 库不发起任何社区或图调用；图分支失败时保留 Chunk 证据继续原流程。DIRECT 路径不查图。
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
    /** 门禁档位计数指标；测试装配可为 null。 */
    private final com.kwiki.graph.persistence.GraphBuildMetrics metrics;

    public GraphRetrievalEnhancer(SnapshotPinner pinner,
                                  GraphSourceInventoryPort inventory,
                                  GraphSnapshotManifestPort manifests,
                                  GraphSourceChildResolverPort childResolver,
                                  CommunityRecallCoordinator communityRecall,
                                  GraphExpansionPort expansion,
                                  int maxHops, int maxEdgesPerNode, int maxEdgesTotal,
                                  int maxEntities, int maxRelations) {
        this(pinner, inventory, manifests, childResolver, communityRecall, expansion,
                maxHops, maxEdgesPerNode, maxEdgesTotal, maxEntities, maxRelations, null);
    }

    public GraphRetrievalEnhancer(SnapshotPinner pinner,
                                  GraphSourceInventoryPort inventory,
                                  GraphSnapshotManifestPort manifests,
                                  GraphSourceChildResolverPort childResolver,
                                  CommunityRecallCoordinator communityRecall,
                                  GraphExpansionPort expansion,
                                  int maxHops, int maxEdgesPerNode, int maxEdgesTotal,
                                  int maxEntities, int maxRelations,
                                  com.kwiki.graph.persistence.GraphBuildMetrics metrics) {
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
        this.metrics = metrics;
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

        // 三档门禁：FULL 维持全部路径；FACTS_ONLY 停用社区、只允许 seed-only；
        // OFF 的库不进入任何社区或图步骤。快照配对自带本 run 固定的 Chunk 版本，
        // 配对不匹配的库在门禁处关闭。
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
        Map<Long, GraphAuthorizationGate.Decision> decisions =
                new GraphKnowledgeBaseGateService().evaluate(gateInputs,
                        (int) pinnedChunkVersion);
        Map<Long, GraphSnapshot> full = new LinkedHashMap<>();
        Map<Long, GraphSnapshot> factsOnly = new LinkedHashMap<>();
        for (Map.Entry<Long, GraphSnapshot> entry : pinnedByKb.entrySet()) {
            GraphAuthorizationGate.Decision decision = decisions.get(entry.getKey());
            if (decision == null) {
                degradations.add("graph-gate-closed");
                continue;
            }
            if (metrics != null) {
                metrics.recordGateMode(decision.mode().name());
            }
            if (decision.mode() == GraphAuthorizationGate.Mode.FULL) {
                full.put(entry.getKey(), entry.getValue());
            } else if (decision.mode() == GraphAuthorizationGate.Mode.FACTS_ONLY) {
                // 每库一条降级项：快照落后或含已退役来源，本库只做逐条回查的 seed-only 扩展
                factsOnly.put(entry.getKey(), entry.getValue());
                degradations.add("graph-facts-only");
            } else {
                degradations.add("graph-gate-closed");
            }
        }
        if (full.isEmpty() && factsOnly.isEmpty()) {
            return finish(chunkChildren, List.of(), degradations, false, budget, startedAt);
        }

        // 社区双路召回：只对 FULL 库执行；FACTS_ONLY 不访问社区索引。
        CommunityRecallCoordinator.RecallOutcome communities = full.isEmpty()
                ? new CommunityRecallCoordinator.RecallOutcome(List.of(), 0, false)
                : communityRecall.recall(query, full, embeddingCache);
        if (communities.anyDegraded()) {
            degradations.add("community-search-degraded");
        }
        Map<Long, List<CommunitySearchHit>> hitsByKb = new LinkedHashMap<>();
        for (CommunityRecallCoordinator.KbRecall recall : communities.perKnowledgeBase()) {
            if (full.containsKey(recall.kbId()) && !recall.communities().isEmpty()) {
                hitsByKb.put(recall.kbId(), recall.communities());
            }
        }

        // 每库回退策略与有界扩展（FULL 与 FACTS_ONLY 都可扩展，扩展模式不同）。
        List<GraphEvidenceFusion.GraphRelationEvidence> graphEvidence = new ArrayList<>();
        LinkedHashMap<Long, GraphSnapshot> expandable = new LinkedHashMap<>();
        expandable.putAll(factsOnly);
        expandable.putAll(full);
        for (Map.Entry<Long, GraphSnapshot> entry : expandable.entrySet()) {
            long kbId = entry.getKey();
            GraphSnapshot snapshot = entry.getValue();
            boolean factsOnlyKb = factsOnly.containsKey(kbId);
            List<GraphSeed> seeds = GraphSeedExtractor.extract(
                    chunkHitsOf(chunkChildren, kbId), snapshot.entityLinkingVersion());
            List<CommunitySearchHit> kbCommunities = hitsByKb.getOrDefault(kbId, List.of());
            boolean serviceFailed = communities.perKnowledgeBase().stream()
                    .anyMatch(recall -> recall.kbId() == kbId && recall.degraded());
            GraphEnhancementFallbackPolicy.Mode mode = GraphEnhancementFallbackPolicy.resolve(
                    true, factsOnlyKb, !seeds.isEmpty(), !kbCommunities.isEmpty(), serviceFailed);
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

        // 图关系来源 → 当前有效 CHILD（ES 回查 + 业务库二次校验），再与 Chunk 融合列表 RRF 合并。
        List<GraphEvidenceFusion.GraphRelationEvidence> resolvable =
                resolveSources(graphEvidence, pinnedByKb, userId, superuser, selectedScope);
        int remainingChildren = MAX_NEW_CHILDREN_PER_RUN - budget.childrenAdded;
        GraphEvidenceFusion.FusionOutcome fused = GraphEvidenceFusion.fuse(
                chunkChildren, resolvable,
                Math.max(chunkChildren.size(), remainingChildren), remainingChildren);

        // 出站 epoch 复核：FULL 库的快照失效即丢弃全部图新增；FACTS_ONLY 库
        // 的 epoch 落后是预期状态，安全性由二次校验逐条保证，不适用本复核。
        boolean epochInvalid = full.entrySet().stream().anyMatch(entry -> {
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

    /**
     * 图关系来源回查与二次校验：先按 sourceChunkId 回查 ES 拿到当前 CHILD，
     * 再对业务库做一次批量二次校验——来源资源必须当前有效（PAGE=ACTIVE、
     * ATTACHMENT=STORED）、用户当前可读且属于本次选择范围。这样覆盖了
     * ES 删除处于 PENDING 时「页面已归档但 Chunk 仍可检索」的窗口；
     * 未通过的 CHILD 丢弃，全部 CHILD 被丢弃的关系整条丢弃。
     * FULL 与 FACTS_ONLY 都执行；批量查询每次调用最多一次 SQL，
     * 耗时计入 run 级图时间预算（由 doEnhance 的 startedAt 统计）。
     */
    private List<GraphEvidenceFusion.GraphRelationEvidence> resolveSources(
            List<GraphEvidenceFusion.GraphRelationEvidence> evidence,
            Map<Long, GraphSnapshot> pinnedByKb,
            long userId, boolean superuser, GraphSelectionScope selectedScope) {
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
        return verifyAgainstBusinessStore(resolvable, userId, superuser, selectedScope);
    }

    /** CHILD 的数据库侧二次校验；返回仍保留至少一个有效 CHILD 的关系。 */
    private List<GraphEvidenceFusion.GraphRelationEvidence> verifyAgainstBusinessStore(
            List<GraphEvidenceFusion.GraphRelationEvidence> relations,
            long userId, boolean superuser, GraphSelectionScope selectedScope) {
        java.util.Set<GraphResourceId> candidates = new java.util.LinkedHashSet<>();
        for (GraphEvidenceFusion.GraphRelationEvidence relation : relations) {
            for (ChildEvidence child : relation.resolvedChildren()) {
                candidates.add(new GraphResourceId(child.resourceType(), child.resourceId()));
            }
        }
        if (candidates.isEmpty()) {
            return relations;
        }
        Map<GraphResourceId, GraphSourceInventoryPort.LifecycleState> states =
                inventory.lifecycleStates(List.copyOf(candidates));
        // 每个资源只判定一次：同一页面常被多条关系、多个 CHILD 引用
        Map<GraphResourceId, Boolean> admitted = new LinkedHashMap<>();
        for (GraphResourceId resource : candidates) {
            boolean ok = states.get(resource) == GraphSourceInventoryPort.LifecycleState.LIVE
                    && selectedScope.covers(resource)
                    && inventory.canRead(userId, superuser, resource);
            admitted.put(resource, ok);
        }
        List<GraphEvidenceFusion.GraphRelationEvidence> verified = new ArrayList<>();
        for (GraphEvidenceFusion.GraphRelationEvidence relation : relations) {
            List<ChildEvidence> kept = new ArrayList<>();
            for (ChildEvidence child : relation.resolvedChildren()) {
                // 已归档/已物理删除、不在选择范围或不可读：内容不得进入模型输入
                if (Boolean.TRUE.equals(admitted.get(
                        new GraphResourceId(child.resourceType(), child.resourceId())))) {
                    kept.add(child);
                }
            }
            if (!kept.isEmpty()) {
                verified.add(relation.withResolvedChildren(kept));
            }
        }
        return verified;
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
