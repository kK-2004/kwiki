package com.kwiki.rag.retrieval;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.wiki.access.AuthorizationScope;
import com.kwiki.wiki.access.ScopeVersionService;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QA 门控知识路径的双分支子召回。每次调用都是一次
 * 独立的混合检索：BM25 与向量在相同服务端
 * ACL + 生命周期过滤器之后再做分支 TopK，两个有序列表
 * 仅在本调用的排名上用标准 RRF（常量 60）融合——先前的
 * 查询或先前 TopK 层级绝不参与——融合结果
 * 裁剪到该阶段的 final TopK，作为携带双分支排名与
 * 融合分数的 {@link ChildEvidence}。查询嵌入在一次
 * 运行内复用；其他内容均不沿用。
 */
@Component
public class QaChildRetrievalService {

    public record StageOutcome(List<ChildEvidence> children, List<String> degradations,
                               int fusedCandidateCount, long elapsedMs) {}

    private final ChunkEmbeddingPort embeddings;
    private final ConcurrentRecallService recall;
    private final ScopeVersionService scopeVersions;
    private final QaRetrievalBudgets budgets;

    public QaChildRetrievalService(ChunkEmbeddingPort embeddings,
                                   ConcurrentRecallService recall,
                                   ScopeVersionService scopeVersions,
                                   QaRetrievalBudgets budgets) {
        this.embeddings = embeddings;
        this.recall = recall;
        this.scopeVersions = scopeVersions;
        this.budgets = budgets;
    }

    /**
     * @param embeddingCache 运行私有的 查询→向量 映射；向量嵌入
     * 每条查询每次运行最多只计算一次
     */
    public StageOutcome retrieveChildren(AuthorizationScope scope, RunContext run,
                                         String query, QaRetrievalBudgets.StageBudget stage,
                                         Map<String, float[]> embeddingCache) {
        long started = System.nanoTime();
        authorize(scope);
        run.authorize();
        var filter = ScopeFilter.from(scope);

        float[] vector = embeddingCache.computeIfAbsent(query, this::embed);
        if (vector == null) {
            embeddingCache.remove(query);
        }

        List<String> degradations = new ArrayList<>();
        Map<String, ChunkHit> hitsByKey = new LinkedHashMap<>();
        List<StandardRrfFusion.RankedList> rankings = new ArrayList<>();
        Map<String, Integer> bm25Order = null;
        Map<String, Integer> vectorOrder = null;

        var outcomes = recall.recall(query, vector, filter, stage.branchTopK(),
                run.timeout(java.time.Duration.ofSeconds(5)).toMillis(),
                RetrievalStrategy.HYBRID);
        for (var outcome : outcomes) {
            if (outcome.degradation() != null) {
                degradations.add(outcome.degradation());
                continue;
            }
            Map<String, Integer> order = new LinkedHashMap<>();
            List<String> keys = new ArrayList<>();
            int rank = 1;
            for (ChunkHit hit : outcome.hits()) {
                hitsByKey.put(hit.chunkKey(), hit);
                if (!order.containsKey(hit.chunkKey())) {
                    order.put(hit.chunkKey(), rank++);
                    keys.add(hit.chunkKey());
                }
            }
            if (outcome.branch() == ChildRecallPort.Branch.BM25) {
                bm25Order = order;
            } else {
                vectorOrder = order;
            }
            rankings.add(new StandardRrfFusion.RankedList(outcome.branch().name(), keys));
        }

        authorize(scope);
        run.authorize();

        var fused = StandardRrfFusion.fuse(rankings, 60);
        List<ChildEvidence> children = new ArrayList<>();
        for (var candidate : fused.stream().limit(stage.finalTopK()).toList()) {
            ChunkHit hit = hitsByKey.get(candidate.chunkKey());
            if (hit == null) {
                continue;
            }
            children.add(ChildEvidence.fromHit(
                    hit,
                    bm25Order == null ? null : bm25Order.get(candidate.chunkKey()),
                    vectorOrder == null ? null : vectorOrder.get(candidate.chunkKey()),
                    candidate.score()));
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return new StageOutcome(List.copyOf(children), List.copyOf(degradations),
                fused.size(), elapsed);
    }

    private void authorize(AuthorizationScope scope) {
        if (scope == null || scope.isStale(scopeVersions::current)) {
            throw new HybridRetrievalOrchestrator.StaleScopeException();
        }
    }

    private float[] embed(String query) {
        try {
            var result = embeddings.embed(List.of(query));
            return result.isEmpty() ? null : result.get(0);
        } catch (Exception e) {
            return null;
        }
    }
}
