package com.kwiki.rag.retrieval;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs the BM25 and vector branches concurrently (virtual threads) with per-branch
 * deadlines. One branch failing or timing out degrades to the other; both failing
 * raises a structured retrieval error. Completion order never matters.
 */
@Component
public class ConcurrentRecallService {

    public record BranchOutcome(ChildRecallPort.Branch branch, List<ChunkHit> hits,
                                String degradation) {
    }

    public static final class RetrievalBranchException extends RuntimeException {
        public RetrievalBranchException(String message) {
            super(message);
        }
    }

    private final ChildRecallPort bm25;
    private final ChildRecallPort vector;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ConcurrentRecallService(@Qualifier("bm25RecallAdapter") ChildRecallPort bm25Adapter,
                                   @Qualifier("vectorRecallAdapter") ChildRecallPort vectorAdapter) {
        this.bm25 = bm25Adapter;
        this.vector = vectorAdapter;
    }

    public List<BranchOutcome> recall(String effectiveQuery, float[] queryVector,
                                      ScopeFilter scopeFilter, int topK, long deadlineMillis) {
        Future<List<ChunkHit>> bm25Future = executor.submit(
                () -> bm25.search(effectiveQuery, null, scopeFilter, topK));
        Future<List<ChunkHit>> vectorFuture = queryVector == null
                ? null
                : executor.submit(() -> vector.search(effectiveQuery, queryVector, scopeFilter, topK));

        List<BranchOutcome> outcomes = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        outcomes.add(collect(ChildRecallPort.Branch.BM25, bm25Future, deadlineMillis, failures));
        if (vectorFuture != null) {
            outcomes.add(collect(ChildRecallPort.Branch.VECTOR, vectorFuture, deadlineMillis,
                    failures));
        } else {
            outcomes.add(new BranchOutcome(ChildRecallPort.Branch.VECTOR, List.of(),
                    "vector-degraded:no-query-embedding"));
        }

        boolean anyUsable = outcomes.stream()
                .anyMatch(outcome -> outcome.degradation() == null && !outcome.hits().isEmpty());
        if (!anyUsable) {
            boolean allFailed = outcomes.stream().noneMatch(
                    outcome -> outcome.degradation() == null);
            if (allFailed) {
                throw new RetrievalBranchException("both branches failed: " + failures);
            }
        }
        return outcomes;
    }

    private BranchOutcome collect(ChildRecallPort.Branch branch, Future<List<ChunkHit>> future,
                                  long deadlineMillis, List<String> failures) {
        try {
            return new BranchOutcome(branch, future.get(deadlineMillis, TimeUnit.MILLISECONDS),
                    null);
        } catch (Exception e) {
            future.cancel(true);
            failures.add(branch.name() + ":" + e.getClass().getSimpleName());
            return new BranchOutcome(branch, List.of(),
                    branch.name().toLowerCase() + "-degraded:" + e.getClass().getSimpleName());
        }
    }
}
