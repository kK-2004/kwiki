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
 * Two-branch child recall for the QA-gated knowledge path. Every call is an
 * independent hybrid retrieval: BM25 and vector run with the same server-side
 * ACL + lifecycle filter before their branch TopK, the two ordered lists are
 * fused with standard RRF (constant 60) on this call's ranks only — previous
 * queries or previous TopK levels never contribute — and the fused result is
 * trimmed to the stage's final TopK as {@link ChildEvidence} carrying both
 * branch ranks and the fused score. Query embeddings are reused within one
 * run; nothing else carries over.
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
     * @param embeddingCache run-owned query→vector map; embeddings are computed
     *                       at most once per query per run
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
