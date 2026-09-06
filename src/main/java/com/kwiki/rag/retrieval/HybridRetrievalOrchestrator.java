package com.kwiki.rag.retrieval;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.rag.rewrite.RewriteResult;
import com.kwiki.wiki.access.AuthorizationScope;
import com.kwiki.wiki.access.ScopeVersionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid retrieval pipeline: embed the query, run scoped child branches per
 * effective query, fuse all successful lists with standard RRF, trim fused
 * children, distinct-expand to authorized parents, and guard the outbound scope
 * version before returning. No rerank and no graph path. Degradation can never
 * widen authorization scope: the filter is identical in every branch.
 */
@Component
public class HybridRetrievalOrchestrator {

    public record RetrievalOutcome(
            List<ParentEvidenceChunk> parents,
            List<String> degradations,
            boolean scopeChanged) {
    }

    public static final class StaleScopeException extends RuntimeException {
        public StaleScopeException() {
            super("authorization scope changed during retrieval");
        }
    }

    private final ChunkEmbeddingPort embeddings;
    private final ConcurrentRecallService recall;
    private final ParentEvidenceResolver parentResolver;
    private final ScopeVersionService scopeVersions;
    private final RetrievalBudgets budgets;

    public HybridRetrievalOrchestrator(ChunkEmbeddingPort embeddings,
                                       ConcurrentRecallService recall,
                                       ParentEvidenceResolver parentResolver,
                                       ScopeVersionService scopeVersions,
                                       RetrievalBudgets budgets) {
        this.embeddings = embeddings;
        this.recall = recall;
        this.parentResolver = parentResolver;
        this.scopeVersions = scopeVersions;
        this.budgets = budgets;
    }

    public RetrievalOutcome retrieve(AuthorizationScope scope, RewriteResult rewrite,
                                     int requestedParentCount, long requestedContextChars) {
        budgets.validateRequest(requestedParentCount, requestedContextChars);
        var scopeFilter = ScopeFilter.from(scope);

        List<String> queries = rewrite.effectiveQueries().isEmpty()
                ? List.of(rewrite.original())
                : rewrite.effectiveQueries().stream().limit(budgets.maxSubqueries).toList();

        float[] queryVector = embed(queries.get(0));

        Map<String, ChunkHit> hitsByKey = new LinkedHashMap<>();
        List<StandardRrfFusion.RankedList> rankedLists = new ArrayList<>();
        List<String> degradations = new ArrayList<>();
        for (String query : queries) {
            List<ConcurrentRecallService.BranchOutcome> outcomes = recall.recall(
                    query, queryVector, scopeFilter, budgets.childBranchTopK,
                    budgets.branchDeadline.toMillis());
            for (ConcurrentRecallService.BranchOutcome outcome : outcomes) {
                if (outcome.degradation() != null) {
                    degradations.add(outcome.degradation());
                    continue;
                }
                List<String> keys = new ArrayList<>();
                for (ChunkHit hit : outcome.hits()) {
                    hitsByKey.put(hit.chunkKey(), hit);
                    keys.add(hit.chunkKey());
                }
                rankedLists.add(new StandardRrfFusion.RankedList(
                        outcome.branch().name(), keys));
            }
        }
        if (rankedLists.isEmpty()) {
            throw new ConcurrentRecallService.RetrievalBranchException("no usable branch");
        }

        List<StandardRrfFusion.FusedChunk> fused = StandardRrfFusion.fuse(rankedLists, 60)
                .stream()
                .limit(budgets.fusedChildLimit)
                .toList();

        List<ParentEvidenceChunk> parents = parentResolver.resolve(
                fused, hitsByKey, scopeFilter, requestedParentCount);

        if (scope.isStale(scopeVersions::current)) {
            throw new StaleScopeException();
        }
        return new RetrievalOutcome(parents, degradations, false);
    }

    private float[] embed(String query) {
        try {
            List<float[]> vectors = embeddings.embed(List.of(query));
            return vectors.isEmpty() ? null : vectors.get(0);
        } catch (Exception e) {
            return null; // vector branch degrades; BM25 still runs
        }
    }
}
