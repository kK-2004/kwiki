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
 * Hybrid retrieval pipeline: embed the query, run scoped child branches per effective query, fuse
 * all successful lists with standard RRF, trim fused children, distinct-expand to authorized
 * parents, and guard the outbound scope version before returning. No rerank and no graph path.
 * Degradation can never widen authorization scope: the filter is identical in every branch.
 */
@Component
public class HybridRetrievalOrchestrator {

    public record RetrievalOutcome(
            List<ParentEvidenceChunk> parents, List<String> degradations, boolean scopeChanged) {}

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

    public HybridRetrievalOrchestrator(
            ChunkEmbeddingPort embeddings,
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

    /** Request-owned rankings and vectors. Never cache evidence across users. */
    public static final class Accumulation {
        public final Map<String, float[]> vectors = new LinkedHashMap<>();
        public final Map<String, ChunkHit> hits = new LinkedHashMap<>();
        public final Map<String, StandardRrfFusion.RankedList> rankings = new LinkedHashMap<>();
    }

    public RetrievalBudgets budgets() {
        return budgets;
    }

    public void authorize(AuthorizationScope scope) {
        if (scope == null || scope.isStale(scopeVersions::current)) throw new StaleScopeException();
    }

    public RetrievalOutcome retrieve(
            AuthorizationScope scope, RewriteResult rewrite, int parentCount, long chars) {
        List<String> queries =
                rewrite.effectiveQueries().isEmpty()
                        ? List.of(rewrite.original())
                        : rewrite.effectiveQueries();
        return retrieve(
                scope,
                new com.kwiki.rag.tool.SearchArguments(
                        queries, RetrievalStrategy.HYBRID, budgets.childBranchTopK),
                new Accumulation(),
                parentCount,
                chars);
    }

    public RetrievalOutcome retrieve(
            AuthorizationScope scope,
            com.kwiki.rag.tool.SearchArguments args,
            Accumulation accumulated,
            int parentCount,
            long chars) {
        budgets.validateRequest(parentCount, chars);
        authorize(scope);
        if (args.topK() < 1
                || args.topK() > budgets.childBranchTopK
                || args.queries().size() > budgets.maxSubqueries)
            throw new IllegalArgumentException("retrieval budget exceeded");
        var filter = ScopeFilter.from(scope);
        var run = com.kwiki.rag.orchestration.RunContext.current();
        List<String> degradations = new ArrayList<>();
        for (String query : args.queries()) {
            if (run != null) run.authorize();
            authorize(scope);
            String vectorKey = embeddings.cacheIdentity() + ":" + query;
            float[] vector = null;
            if (args.strategy() != RetrievalStrategy.BM25) {
                vector = accumulated.vectors.get(vectorKey);
                if (vector == null) {
                    vector = embed(query);
                    if (vector != null) accumulated.vectors.put(vectorKey, vector);
                }
                if (vector == null && args.strategy() == RetrievalStrategy.VECTOR)
                    throw new com.kwiki.rag.orchestration.RunFailure("vector-unavailable");
            }
            long deadline =
                    run == null
                            ? budgets.branchDeadline.toMillis()
                            : run.timeout(budgets.branchDeadline).toMillis();
            var outcomes =
                    recall.recall(query, vector, filter, args.topK(), deadline, args.strategy());
            for (var outcome : outcomes) {
                if (outcome.degradation() != null) {
                    degradations.add(outcome.degradation());
                    continue;
                }
                var keys = new ArrayList<String>();
                for (var hit : outcome.hits()) {
                    accumulated.hits.put(hit.chunkKey(), hit);
                    if (!keys.contains(hit.chunkKey())) keys.add(hit.chunkKey());
                }
                String source = query + ":" + outcome.branch() + ":" + args.topK();
                accumulated.rankings.putIfAbsent(
                        source, new StandardRrfFusion.RankedList(outcome.branch().name(), keys));
            }
        }
        authorize(scope);
        if (run != null) run.authorize();
        var fused =
                StandardRrfFusion.fuse(new ArrayList<>(accumulated.rankings.values()), 60).stream()
                        .limit(budgets.fusedChildLimit)
                        .toList();
        var parents = parentResolver.resolve(fused, accumulated.hits, filter, parentCount);
        authorize(scope);
        if (run != null) run.authorize();
        return new RetrievalOutcome(
                parents, List.copyOf(new java.util.LinkedHashSet<>(degradations)), false);
    }

    private float[] embed(String query) {
        try {
            var result = embeddings.embed(List.of(query));
            return result.isEmpty() ? null : result.get(0);
        } catch (Exception e) {
            var run = com.kwiki.rag.orchestration.RunContext.current();
            if (run != null) run.check();
            return null;
        }
    }
}
