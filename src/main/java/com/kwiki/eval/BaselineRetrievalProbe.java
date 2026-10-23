package com.kwiki.eval;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.rag.retrieval.ChildRecallPort;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.ConcurrentRecallService;
import com.kwiki.rag.retrieval.QaRetrievalBudgets;
import com.kwiki.rag.retrieval.RetrievalStrategy;
import com.kwiki.rag.retrieval.ScopeFilter;
import com.kwiki.rag.retrieval.StandardRrfFusion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 原始 query 的检索基线（不改写、不走图）：与 QA 阶梯同口径——基础阶段两路各
 * baseBranchTopK 融合取 baseFinalTopK，扩大阶段两路各 expandedBranchTopK 融合取
 * expandedFinalTopK；扩大阶段的两路原始列表即单路候选池。查询向量只计算一次。
 */
@Component
public class BaselineRetrievalProbe {

    static final long BRANCH_TIMEOUT_MS = 10_000;

    public record Result(List<ChunkHit> bm25, List<ChunkHit> vector, List<ChunkHit> fused8,
                         List<ChunkHit> fused20, List<String> degradations) {

        public Map<EvalLayer, List<ChunkHit>> byLayer() {
            Map<EvalLayer, List<ChunkHit>> layers = new EnumMap<>(EvalLayer.class);
            layers.put(EvalLayer.BM25_50, bm25);
            layers.put(EvalLayer.VECTOR_50, vector);
            layers.put(EvalLayer.FUSED_8, fused8);
            layers.put(EvalLayer.FUSED_20, fused20);
            return layers;
        }
    }

    private final ChunkEmbeddingPort embeddings;
    private final ConcurrentRecallService recall;
    private final QaRetrievalBudgets budgets;

    public BaselineRetrievalProbe(ChunkEmbeddingPort embeddings, ConcurrentRecallService recall,
                                  QaRetrievalBudgets budgets) {
        this.embeddings = embeddings;
        this.recall = recall;
        this.budgets = budgets;
    }

    public Result probe(String query, ScopeFilter filter) {
        LinkedHashSet<String> degradations = new LinkedHashSet<>();
        float[] vector = embed(query, degradations);
        var stages = budgets.snapshot();
        var base = recall.recall(query, vector, filter, stages.baseBranchTopK(),
                BRANCH_TIMEOUT_MS, RetrievalStrategy.HYBRID);
        var expanded = recall.recall(query, vector, filter, stages.expandedBranchTopK(),
                BRANCH_TIMEOUT_MS, RetrievalStrategy.HYBRID);
        Map<String, ChunkHit> hits = new LinkedHashMap<>();
        for (var outcome : base) remember(outcome, hits, degradations);
        List<ChunkHit> bm25 = List.of();
        List<ChunkHit> vectorHits = List.of();
        for (var outcome : expanded) {
            remember(outcome, hits, degradations);
            if (outcome.degradation() != null) continue;
            if (outcome.branch() == ChildRecallPort.Branch.BM25) bm25 = List.copyOf(outcome.hits());
            else vectorHits = List.copyOf(outcome.hits());
        }
        return new Result(bm25, vectorHits,
                fuse(base, hits, stages.baseFinalTopK()),
                fuse(expanded, hits, stages.expandedFinalTopK()),
                List.copyOf(degradations));
    }

    private float[] embed(String query, LinkedHashSet<String> degradations) {
        try {
            List<float[]> vectors = embeddings.embed(List.of(query));
            if (!vectors.isEmpty()) return vectors.get(0);
        } catch (RuntimeException ignored) {
            // 向量不可用时与线上一致：降级为 BM25 单路
        }
        degradations.add("vector-unavailable");
        return null;
    }

    private static void remember(ConcurrentRecallService.BranchOutcome outcome, Map<String, ChunkHit> hits,
                                 LinkedHashSet<String> degradations) {
        if (outcome.degradation() != null) {
            degradations.add(outcome.degradation());
            return;
        }
        outcome.hits().forEach(hit -> hits.putIfAbsent(hit.chunkKey(), hit));
    }

    private static List<ChunkHit> fuse(List<ConcurrentRecallService.BranchOutcome> outcomes,
                                       Map<String, ChunkHit> hits, int limit) {
        List<StandardRrfFusion.RankedList> rankings = new ArrayList<>();
        for (var outcome : outcomes) {
            if (outcome.degradation() != null) continue;
            rankings.add(new StandardRrfFusion.RankedList(outcome.branch().name(),
                    outcome.hits().stream().map(ChunkHit::chunkKey).distinct().toList()));
        }
        return StandardRrfFusion.fuse(rankings, 60).stream()
                .limit(limit)
                .map(fused -> hits.get(fused.chunkKey()))
                .filter(Objects::nonNull)
                .toList();
    }
}
