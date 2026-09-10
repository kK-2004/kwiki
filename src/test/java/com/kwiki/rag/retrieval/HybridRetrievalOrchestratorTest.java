package com.kwiki.rag.retrieval;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.rag.rewrite.RewriteResult;
import com.kwiki.rag.routing.RewriteMode;
import com.kwiki.wiki.access.AuthorizationScope;
import com.kwiki.wiki.access.ScopeVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 以可控假实现验证编排器行为：两个分支都在排序之前
 * 应用授权，单分支降级与父分块扩展绝不
 * 扩大作用域，两个分支都失败会抛出结构化错误，缺失/未授权的
 * 父分块会被省略，且作用域版本变化会在证据外发之前中止。
 */
class HybridRetrievalOrchestratorTest {

    private static ChunkHit hit(String chunkKey, String parentKey, long kbId) {
        return new ChunkHit(chunkKey, parentKey, kbId, "PAGE", 1L, 1L, "标题", 0, 10, "内容 " + chunkKey);
    }

    private static ParentEvidenceChunk parent(String parentKey, long kbId) {
        return new ParentEvidenceChunk(parentKey, kbId, "PAGE", 1L, 1L, "标题",
                "父块内容 " + parentKey, 0.0, List.of());
    }

    /** 假分支按静态映射排序；只返回作用域内的候选。 */
    private static class ScriptedBranch implements ChildRecallPort {
        final ChildRecallPort.Branch branch;
        final Map<String, List<ChunkHit>> hitsByQuery;
        final Set<Long> allowedKbIds;
        RuntimeException failure;

        ScriptedBranch(ChildRecallPort.Branch branch, Map<String, List<ChunkHit>> hitsByQuery,
                       Set<Long> allowedKbIds) {
            this.branch = branch;
            this.hitsByQuery = hitsByQuery;
            this.allowedKbIds = allowedKbIds;
        }

        @Override
        public List<ChunkHit> search(String effectiveQuery, float[] queryVector,
                                     ScopeFilter scopeFilter, int topK) {
            if (failure != null) {
                throw failure;
            }
            List<ChunkHit> hits = new ArrayList<>();
            for (ChunkHit hit : hitsByQuery.getOrDefault(effectiveQuery, List.of())) {
                if (allowedKbIds.contains(hit.kbId())) {
                    hits.add(hit); // 作用域在 TopK 之前应用：作用域外的候选绝不参与排序
                }
                if (hits.size() == topK) {
                    break;
                }
            }
            return hits;
        }
    }

    private static ChunkEmbeddingPort embeddings(float[] vector) {
        return texts -> List.of(vector);
    }

    private static final float[] VECTOR = new float[]{0.1f, 0.2f};

    private static ScopeVersionService scopeVersions() {
        ObjectProvider<org.springframework.jdbc.core.JdbcOperations> provider =
                new ObjectProvider<>() {
                    @Override
                    public org.springframework.jdbc.core.JdbcOperations getIfAvailable() {
                        return null;
                    }
                };
        return new ScopeVersionService(provider);
    }

    private HybridRetrievalOrchestrator orchestrator(ChildRecallPort bm25, ChildRecallPort vector,
                                                     ParentEvidenceResolver.ParentChunkFetcher fetcher,
                                                     ChunkEmbeddingPort embeddingPort) {
        return new HybridRetrievalOrchestrator(
                embeddingPort,
                new ConcurrentRecallService(bm25, vector),
                new ParentEvidenceResolver(Optional.ofNullable(fetcher)),
                scopeVersions(),
                new RetrievalBudgets(50, 40, 8, 3, 24000, java.time.Duration.ofSeconds(5)));
    }

    private RewriteResult query(String effective) {
        return new RewriteResult(effective, RewriteMode.NONE, List.of(effective), null);
    }

    private AuthorizationScope scope(Set<Long> kbIds, Map<Long, Long> versions) {
        return new AuthorizationScope(7L, false, kbIds, versions);
    }

    @Test
    void restrictedChunksNeverOccupyARankInEitherBranch() {
        // 分块 R2 位于知识库 99（作用域外），在两个分支中本都会排第一
        Map<String, List<ChunkHit>> bm25Hits = Map.of("安全", List.of(
                hit("R2", "P-R2", 99L), hit("S1", "P-S1", 1L)));
        Map<String, List<ChunkHit>> vectorHits = Map.of("安全", List.of(
                hit("R2", "P-R2", 99L), hit("S1", "P-S1", 1L)));

        HybridRetrievalOrchestrator orchestrator = orchestrator(
                new ScriptedBranch(ChildRecallPort.Branch.BM25, bm25Hits, Set.of(1L)),
                new ScriptedBranch(ChildRecallPort.Branch.VECTOR, vectorHits, Set.of(1L)),
                (keys, scopeFilter) -> keys.stream().filter(key -> !key.contains("R2"))
                        .map(key -> parent(key, 1L)).toList(),
                embeddings(VECTOR));

        var outcome = orchestrator.retrieve(scope(Set.of(1L), Map.of(1L, 1L)),
                query("安全"), 8, 24000);

        assertThat(outcome.parents()).extracting(ParentEvidenceChunk::parentChunkKey)
                .doesNotContain("P-R2");
        assertThat(outcome.degradations()).isEmpty();
    }

    @Test
    void bm25FailureDegradesToVectorWithoutScopeWidening() {
        ScriptedBranch bm25 = new ScriptedBranch(ChildRecallPort.Branch.BM25, Map.of(),
                Set.of(1L));
        bm25.failure = new RuntimeException("es timeout");
        ScriptedBranch vector = new ScriptedBranch(ChildRecallPort.Branch.VECTOR,
                Map.of("部署", List.of(hit("V1", "P-V1", 1L))), Set.of(1L));

        var outcome = orchestrator(bm25, vector,
                (keys, scopeFilter) -> keys.stream().map(key -> parent(key, 1L)).toList(),
                embeddings(VECTOR))
                .retrieve(scope(Set.of(1L), Map.of(1L, 1L)), query("部署"), 8, 24000);

        assertThat(outcome.degradations()).anyMatch(reason -> reason.startsWith("bm25-degraded"));
        assertThat(outcome.parents()).extracting(ParentEvidenceChunk::parentChunkKey)
                .containsExactly("P-V1");
    }

    @Test
    void embeddingFailureStillRunsBm25() {
        Map<String, List<ChunkHit>> bm25Hits = Map.of("部署", List.of(hit("B1", "P-B1", 1L)));
        var outcome = orchestrator(
                new ScriptedBranch(ChildRecallPort.Branch.BM25, bm25Hits, Set.of(1L)),
                new ScriptedBranch(ChildRecallPort.Branch.VECTOR, Map.of(), Set.of(1L)),
                (keys, scopeFilter) -> keys.stream().map(key -> parent(key, 1L)).toList(),
                texts -> { throw new IllegalStateException("qwen down"); })
                .retrieve(scope(Set.of(1L), Map.of(1L, 1L)), query("部署"), 8, 24000);

        assertThat(outcome.parents()).extracting(ParentEvidenceChunk::parentChunkKey)
                .containsExactly("P-B1");
        assertThat(outcome.degradations())
                .anyMatch(reason -> reason.contains("no-query-embedding"));
    }

    @Test
    void bothBranchesFailingRaisesStructuredError() {
        ScriptedBranch bm25 = new ScriptedBranch(ChildRecallPort.Branch.BM25, Map.of(), Set.of(1L));
        bm25.failure = new RuntimeException("down");
        ScriptedBranch vector = new ScriptedBranch(ChildRecallPort.Branch.VECTOR, Map.of(),
                Set.of(1L));
        vector.failure = new RuntimeException("down");

        assertThatThrownBy(() -> orchestrator(bm25, vector, (keys, scopeFilter) -> List.of(),
                embeddings(VECTOR))
                .retrieve(scope(Set.of(1L), Map.of(1L, 1L)), query("部署"), 8, 24000))
                .isInstanceOf(ConcurrentRecallService.RetrievalBranchException.class)
                .hasMessageContaining("both branches failed");
    }

    @Test
    void winningChildrenSharingOneParentCollapseToSingleEvidence() {
        Map<String, List<ChunkHit>> hits = Map.of("安全", List.of(
                hit("C1", "P-A", 1L), hit("C2", "P-A", 1L), hit("C3", "P-B", 1L)));

        var outcome = orchestrator(
                new ScriptedBranch(ChildRecallPort.Branch.BM25, hits, Set.of(1L)),
                new ScriptedBranch(ChildRecallPort.Branch.VECTOR, hits, Set.of(1L)),
                (keys, scopeFilter) -> keys.stream().map(key -> parent(key, 1L)).toList(),
                embeddings(VECTOR))
                .retrieve(scope(Set.of(1L), Map.of(1L, 1L)), query("安全"), 8, 24000);

        assertThat(outcome.parents()).extracting(ParentEvidenceChunk::parentChunkKey)
                .containsExactly("P-A", "P-B");
        ParentEvidenceChunk shared = outcome.parents().get(0);
        assertThat(shared.matchedChildren()).extracting(ChunkHit::chunkKey)
                .containsExactlyInAnyOrder("C1", "C2");
        assertThat(shared.bestRrfScore()).isGreaterThan(0);
    }

    @Test
    void parentLimitTruncatesByFirstChildOrder() {
        Map<String, List<ChunkHit>> hits = Map.of("多主题", List.of(
                hit("C1", "P-1", 1L), hit("C2", "P-2", 1L), hit("C3", "P-3", 1L)));

        var outcome = orchestrator(
                new ScriptedBranch(ChildRecallPort.Branch.BM25, hits, Set.of(1L)),
                new ScriptedBranch(ChildRecallPort.Branch.VECTOR, Map.of("多主题", List.of()),
                        Set.of(1L)),
                (keys, scopeFilter) -> keys.stream().map(key -> parent(key, 1L)).toList(),
                embeddings(VECTOR))
                .retrieve(scope(Set.of(1L), Map.of(1L, 1L)), query("多主题"), 2, 24000);

        assertThat(outcome.parents()).extracting(ParentEvidenceChunk::parentChunkKey)
                .containsExactly("P-1", "P-2");
    }

    @Test
    void missingOrUnauthorizedParentsAreOmittedNotSubstituted() {
        Map<String, List<ChunkHit>> hits = Map.of("安全", List.of(
                hit("C1", "P-present", 1L), hit("C2", "P-gone", 1L)));

        var outcome = orchestrator(
                new ScriptedBranch(ChildRecallPort.Branch.BM25, hits, Set.of(1L)),
                new ScriptedBranch(ChildRecallPort.Branch.VECTOR, hits, Set.of(1L)),
                (keys, scopeFilter) -> keys.stream().filter(key -> !key.contains("gone"))
                        .map(key -> parent(key, 1L)).toList(),
                embeddings(VECTOR))
                .retrieve(scope(Set.of(1L), Map.of(1L, 1L)), query("安全"), 8, 24000);

        assertThat(outcome.parents()).extracting(ParentEvidenceChunk::parentChunkKey)
                .containsExactly("P-present");
    }

    @Test
    void scopeVersionChangeAbortsBeforeEvidenceLeaves() {
        ScopeVersionService versions = scopeVersions();
        versions.bump(1L); // 当前 = 2

        HybridRetrievalOrchestrator orchestrator = new HybridRetrievalOrchestrator(
                embeddings(VECTOR),
                new ConcurrentRecallService(
                        new ScriptedBranch(ChildRecallPort.Branch.BM25,
                                Map.of("安全", List.of(hit("C1", "P-A", 1L))), Set.of(1L)),
                        new ScriptedBranch(ChildRecallPort.Branch.VECTOR,
                                Map.of("安全", List.of(hit("C1", "P-A", 1L))), Set.of(1L))),
                new ParentEvidenceResolver(Optional.of(
                        (keys, scopeFilter) -> keys.stream().map(key -> parent(key, 1L)).toList())),
                versions,
                new RetrievalBudgets(50, 40, 8, 3, 24000, java.time.Duration.ofSeconds(5)));

        // 作用域捕获的是版本 1，但世界已经变成 2 -> 中止
        assertThatThrownBy(() -> orchestrator.retrieve(
                new AuthorizationScope(7L, false, Set.of(1L), Map.of(1L, 1L)),
                query("安全"), 8, 24000))
                .isInstanceOf(HybridRetrievalOrchestrator.StaleScopeException.class);
    }

    @Test
    void multiSubqueryFusionCombinesListsByStableKey() {
        Map<String, List<ChunkHit>> bm25Hits = new LinkedHashMap<>();
        bm25Hits.put("什么是知识库", List.of(hit("K1", "P-K", 1L)));
        bm25Hits.put("怎么导出页面", List.of(hit("E1", "P-E", 1L)));

        var outcome = orchestrator(
                new ScriptedBranch(ChildRecallPort.Branch.BM25, bm25Hits, Set.of(1L)),
                new ScriptedBranch(ChildRecallPort.Branch.VECTOR, Map.of(), Set.of(1L)),
                (keys, scopeFilter) -> keys.stream().map(key -> parent(key, 1L)).toList(),
                embeddings(VECTOR))
                .retrieve(scope(Set.of(1L), Map.of(1L, 1L)),
                        new RewriteResult("什么是知识库？另外怎么导出页面",
                                RewriteMode.DECOMPOSITION,
                                List.of("什么是知识库", "怎么导出页面"), null),
                        8, 24000);

        assertThat(outcome.parents()).extracting(ParentEvidenceChunk::parentChunkKey)
                .containsExactlyInAnyOrder("P-K", "P-E");
    }
}
