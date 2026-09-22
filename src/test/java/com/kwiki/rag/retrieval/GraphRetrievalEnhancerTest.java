package com.kwiki.rag.retrieval;

import com.kwiki.graph.CommunityRecallCoordinator;
import com.kwiki.graph.CommunitySearchHit;
import com.kwiki.graph.CommunitySearchPort;
import com.kwiki.graph.CommunitySearchRequest;
import com.kwiki.graph.CommunitySearchResult;
import com.kwiki.graph.GraphExpansionPort;
import com.kwiki.graph.GraphExpansionRequest;
import com.kwiki.graph.GraphExpansionResult;
import com.kwiki.graph.GraphPredicate;
import com.kwiki.graph.GraphRelation;
import com.kwiki.graph.GraphResourceId;
import com.kwiki.graph.GraphSelectionScope;
import com.kwiki.graph.GraphSeed;
import com.kwiki.graph.GraphSnapshot;
import com.kwiki.graph.GraphSnapshotPin;
import com.kwiki.graph.GraphSnapshotPinGuard;
import com.kwiki.graph.GraphSourceChildResolverPort;
import com.kwiki.graph.GraphSourceInventoryPort;
import com.kwiki.graph.GraphSourceRef;
import com.kwiki.graph.GraphSnapshotManifestPort;
import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 11.7 图增强失败语义验证：单支失败保留 Chunk、只有摘要不能回答、
 * 来源失效无法桥接、回调不重复加分、多轮预算不重置、DIRECT 不查图、
 * 门禁拒绝的库社区与图调用为零。
 */
class GraphRetrievalEnhancerTest {

    private static final long KB = 7;
    private static final String GRAPH_SOURCE = "sc_graph";
    private static final String ENTITY_VERSION = "entity-linking-v1";

    // ---- 固定测试夹具 ----

    private static GraphSnapshot snapshot() {
        return new GraphSnapshot(91, KB, 42, 3, 5, "kwiki-chunks-v3",
                "kwiki-communities-v5-kb7", ENTITY_VERSION,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 5, 2);
    }

    private static ChildEvidence chunkChild(String key, String content) {
        return new ChildEvidence(key, "p-" + key, KB, "PAGE", 100 + key.hashCode()
                % 1000, 4L, "heading", 0, content.length(), content,
                1, null, 0.02, null, List.of(), null,
                com.kwiki.rag.retrieval.EntityLinkingStatus.MISSING);
    }

    private static ChildEvidence seedChild(String key) {
        return new ChildEvidence(key, "p-" + key, KB, "PAGE", 9, 4L, "heading", 0, 10,
                "已命中的种子正文", 1, 1, 0.05, GRAPH_SOURCE,
                List.of("e_seed"), ENTITY_VERSION,
                com.kwiki.rag.retrieval.EntityLinkingStatus.READY);
    }

    private static CommunitySearchHit community(double score) {
        return new CommunitySearchHit(KB, 42, 5, "c_1", "主题", "摘要",
                List.of("e_seed"), List.of(new GraphSourceRef(GRAPH_SOURCE, 0, 1)), score);
    }

    /** 可编程环境：记录社区与图调用次数，供零调用断言。 */
    private static final class Env {
        final List<ChildEvidence> children = List.of(seedChild("k1"), chunkChild("k2", "正文"));
        final Map<String, float[]> embeddingCache = new HashMap<>();
        int communityCalls;
        int expansionCalls;
        CommunitySearchResult communityResult =
                new CommunitySearchResult(List.of(community(3.0)), false, "");
        GraphExpansionResult expansionResult = expansion();
        boolean expansionFails;
        boolean resolveFails;
        boolean gateClosed;
        boolean epochAdvanced;
        GraphSourceInventoryPort inventory = new GraphSourceInventoryPort() {
            @Override
            public List<GraphResourceId> listCurrentSources(long kbId) {
                return List.of(GraphResourceId.page(9));
            }

            @Override
            public boolean canRead(long userId, boolean superuser, GraphResourceId resource) {
                return !gateClosed;
            }

            @Override
            public long[] currentEpochs(long kbId) {
                return epochAdvanced ? new long[]{6, 2} : new long[]{5, 2};
            }
        };
        CommunitySearchPort communitySearch = request -> {
            communityCalls++;
            return communityResult;
        };
        GraphExpansionPort expansion = request -> {
            expansionCalls++;
            if (expansionFails) {
                throw new IllegalStateException("graph down");
            }
            return expansionResult;
        };
        GraphSourceChildResolverPort resolver = (index, id, version, kb) -> {
            if (resolveFails || !GRAPH_SOURCE.equals(id) || !ENTITY_VERSION.equals(version)) {
                return Optional.empty();
            }
            return Optional.of(new ChunkHit("k9", "p-k9", KB, "PAGE", 9, 4L, "heading",
                    0, 8, "图来源正文", List.of(), GRAPH_SOURCE, List.of("e_seed"),
                    ENTITY_VERSION, com.kwiki.rag.retrieval.EntityLinkingStatus.READY));
        };

        GraphRetrievalEnhancer enhancer() {
            return new GraphRetrievalEnhancer(
                    new GraphRetrievalEnhancer.SnapshotPinner() {
                        @Override
                        public Map<Long, Optional<GraphSnapshotPin>> pinAll(
                                java.util.Collection<Long> kbIds) {
                            Map<Long, Optional<GraphSnapshotPin>> pins = new LinkedHashMap<>();
                            for (Long kbId : kbIds) {
                                pins.put(kbId, Optional.of(GraphSnapshotPinGuard.pin(
                                        snapshot(), 555L, 3)));
                            }
                            return pins;
                        }

                        @Override
                        public void release(Iterable<GraphSnapshotPin> pins) {
                        }
                    },
                    inventory,
                    (GraphSnapshotManifestPort) snapshotId -> List.of(GraphResourceId.page(9)),
                    resolver,
                    new CommunityRecallCoordinator(communitySearch, embedding()),
                    expansion, 2, 50, 500, 20, 30);
        }

        private static ChunkEmbeddingPort embedding() {
            return new ChunkEmbeddingPort() {
                @Override
                public String cacheIdentity() {
                    return "test-model:1024";
                }

                @Override
                public List<float[]> embed(List<String> texts) {
                    return texts.stream().map(text -> new float[]{0.1f, 0.2f}).toList();
                }
            };
        }

        private static GraphExpansionResult expansion() {
            GraphRelation relation = new GraphRelation("r_1", "e_seed",
                    GraphPredicate.USES, "e_other", 0.9,
                    List.of(new GraphSourceRef(GRAPH_SOURCE, 0, 1)));
            return new GraphExpansionResult(List.of(), List.of(relation), 12, false);
        }
    }

    @Test
    void healthyPathAddsGraphChildrenAndCapsBudgets() {
        Env env = new Env();
        GraphRetrievalEnhancer.SharedBudget budget = new GraphRetrievalEnhancer.SharedBudget();

        var result = env.enhancer().enhance(42, false, env.children, "查询",
                GraphSelectionScope.all(), env.embeddingCache, budget, false);

        assertThat(result.enhanced()).isTrue();
        assertThat(result.mergedChildren()).extracting(ChildEvidence::chunkKey)
                .contains("k1", "k2", "k9");
        assertThat(budget.childrenAdded()).isEqualTo(1);
        assertThat(budget.edgeChecks()).isEqualTo(12);
        assertThat(env.communityCalls).isEqualTo(1);
        assertThat(env.expansionCalls).isEqualTo(1);
    }

    @Test
    void directAnswerNeverTouchesCommunityOrGraph() {
        Env env = new Env();

        var result = env.enhancer().enhance(42, false, env.children, "查询",
                GraphSelectionScope.all(), env.embeddingCache,
                new GraphRetrievalEnhancer.SharedBudget(), true);

        assertThat(result.enhanced()).isFalse();
        assertThat(env.communityCalls).isZero();
        assertThat(env.expansionCalls).isZero();
    }

    @Test
    void gateClosedKnowledgeBaseMakesZeroCommunityAndGraphCalls() {
        Env env = new Env();
        env.gateClosed = true;

        var result = env.enhancer().enhance(42, false, env.children, "查询",
                GraphSelectionScope.all(), env.embeddingCache,
                new GraphRetrievalEnhancer.SharedBudget(), false);

        assertThat(result.enhanced()).isFalse();
        assertThat(result.degradations()).contains("graph-gate-closed");
        assertThat(env.communityCalls).isZero();
        assertThat(env.expansionCalls).isZero();
        assertThat(result.mergedChildren()).isEqualTo(env.children);
    }

    @Test
    void communityServiceFailureAllowsSeedOnlyAndKeepsChunkEvidence() {
        Env env = new Env();
        env.communityResult = new CommunitySearchResult(List.of(), true,
                "community-search-branch-failed");

        var result = env.enhancer().enhance(42, false, env.children, "查询",
                GraphSelectionScope.all(), env.embeddingCache,
                new GraphRetrievalEnhancer.SharedBudget(), false);

        assertThat(result.degradations()).contains("community-search-degraded");
        assertThat(env.expansionCalls).isEqualTo(1);
        assertThat(result.mergedChildren()).extracting(ChildEvidence::chunkKey)
                .contains("k1", "k2");
    }

    @Test
    void expansionFailureKeepsChunkEvidenceAndRecordsDegradation() {
        Env env = new Env();
        env.expansionFails = true;

        var result = env.enhancer().enhance(42, false, env.children, "查询",
                GraphSelectionScope.all(), env.embeddingCache,
                new GraphRetrievalEnhancer.SharedBudget(), false);

        assertThat(result.enhanced()).isFalse();
        assertThat(result.degradations()).contains("graph-expansion-failed");
        assertThat(result.mergedChildren()).isEqualTo(env.children);
    }

    @Test
    void summaryAloneCannotAnswerWithoutVerifiedRepresentativeSource() {
        Env env = new Env();
        // 无 Chunk 种子：把种子证据换成无实体元数据的普通命中。
        List<ChildEvidence> noSeeds = List.of(chunkChild("k2", "正文"));
        env.resolveFails = true; // 代表实体来源不可解析

        var result = env.enhancer().enhance(42, false, noSeeds, "查询",
                GraphSelectionScope.all(), env.embeddingCache,
                new GraphRetrievalEnhancer.SharedBudget(), false);

        assertThat(result.enhanced()).isFalse();
        assertThat(result.degradations()).contains("representative-source-unavailable");
        assertThat(env.expansionCalls).isZero();
        assertThat(result.mergedChildren()).isEqualTo(noSeeds);
    }

    @Test
    void invalidatedRelationSourceCannotBridgeIntoEvidence() {
        Env env = new Env();
        env.resolveFails = true;

        var result = env.enhancer().enhance(42, false, env.children, "查询",
                GraphSelectionScope.all(), env.embeddingCache,
                new GraphRetrievalEnhancer.SharedBudget(), false);

        assertThat(result.enhanced()).isFalse();
        // 未解析出任何当前有效来源：图断言不能桥接进证据，Chunk 列表保持。
        assertThat(result.mergedChildren()).extracting(ChildEvidence::chunkKey)
                .containsExactly("k1", "k2");
        assertThat(env.expansionCalls).isEqualTo(1);
    }

    @Test
    void epochInvalidationDuringRunDropsGraphAdditionsButKeepsChunks() {
        Env env = new Env();
        env.epochAdvanced = true;

        var result = env.enhancer().enhance(42, false, env.children, "查询",
                GraphSelectionScope.all(), env.embeddingCache,
                new GraphRetrievalEnhancer.SharedBudget(), false);

        // epoch 变化首先使全来源门禁关闭（source-epoch-stale），
        // 不进入任何社区或图步骤，更不会以降级方式继续输出。
        assertThat(result.enhanced()).isFalse();
        assertThat(result.degradations()).contains("graph-gate-closed");
        assertThat(env.communityCalls).isZero();
        assertThat(env.expansionCalls).isZero();
        assertThat(result.mergedChildren()).extracting(ChildEvidence::chunkKey)
                .containsExactly("k1", "k2");
    }

    @Test
    void sharedBudgetIsNeverResetAcrossRoundsAndRetryDoesNotDoubleCount() {
        Env env = new Env();
        GraphRetrievalEnhancer.SharedBudget budget = new GraphRetrievalEnhancer.SharedBudget();
        GraphRetrievalEnhancer enhancer = env.enhancer();
        Map<String, float[]> cache = env.embeddingCache;

        var first = enhancer.enhance(42, false, env.children, "查询",
                GraphSelectionScope.all(), cache, budget, false);
        assertThat(first.enhanced()).isTrue();
        assertThat(budget.childrenAdded()).isEqualTo(1);

        // 同轮回调重试携带上一轮已合并列表：k9 已存在，不重复加分/新增。
        var retried = enhancer.enhance(42, false, first.mergedChildren(), "查询",
                GraphSelectionScope.all(), cache, budget, false);
        assertThat(retried.enhanced()).isFalse();
        assertThat(retried.mergedChildren()).extracting(ChildEvidence::chunkKey)
                .containsExactlyInAnyOrder("k1", "k2", "k9");
        assertThat(budget.childrenAdded()).isEqualTo(1);

        // 累计到 run 级 5 个新增 CHILD 上限后不再发起任何图调用。
        List<ChildEvidence> fresh = new ArrayList<>(env.children);
        for (int round = 0; round < 5; round++) {
            enhancer.enhance(42, false, fresh, "查询-" + round,
                    GraphSelectionScope.all(), cache, budget, false);
        }
        assertThat(budget.childrenAdded()).isEqualTo(
                GraphRetrievalEnhancer.MAX_NEW_CHILDREN_PER_RUN);
        int callsAtCap = env.expansionCalls;
        enhancer.enhance(42, false, fresh, "再查询", GraphSelectionScope.all(),
                cache, budget, false);
        assertThat(env.expansionCalls).isEqualTo(callsAtCap);
        assertThat(budget.usedMs()).isGreaterThanOrEqualTo(0);
    }
}
