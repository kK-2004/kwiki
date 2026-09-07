package com.kwiki.rag.retrieval;

import static org.assertj.core.api.Assertions.*;

import com.kwiki.rag.tool.SearchArguments;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class AgenticRetrievalStrategiesTest {
    final AuthorizationScope scope = new AuthorizationScope(1, false, Set.of(1L), Map.of(1L, 1L));
    final RetrievalBudgets budgets =
            new RetrievalBudgets(50, 40, 8, 3, 24000, Duration.ofSeconds(1));

    @Test
    void eachQueryUsesItsOwnVectorAndRepeatedListsDoNotInflateRrf() {
        Map<String, Float> seen = new java.util.concurrent.ConcurrentHashMap<>();
        var embeddings = new AtomicInteger();
        ChildRecallPort branch =
                (q, v, f, k) -> {
                    if (v != null) seen.put(q, v[0]);
                    return List.of(new ChunkHit("C", "P", 1, "PAGE", 1, 1L, "h", 0, 4, "body"));
                };
        var retrieval =
                new HybridRetrievalOrchestrator(
                        texts -> {
                            embeddings.incrementAndGet();
                            return List.of(new float[] {texts.getFirst().equals("a") ? 1 : 2});
                        },
                        new ConcurrentRecallService(branch, branch),
                        new ParentEvidenceResolver(
                                Optional.of(
                                        (keys, f) ->
                                                List.of(
                                                        new ParentEvidenceChunk(
                                                                "P", 1, "PAGE", 1, 1L, "h", "body",
                                                                0, List.of())))),
                        new ScopeVersionService(StandardTestProperties.nullProvider()),
                        budgets);
        var accumulated = new HybridRetrievalOrchestrator.Accumulation();
        var arguments = new SearchArguments(List.of("a", "b"), RetrievalStrategy.HYBRID, 20);
        var first = retrieval.retrieve(scope, arguments, accumulated, 8, 24000);
        var repeated = retrieval.retrieve(scope, arguments, accumulated, 8, 24000);
        assertThat(seen).containsEntry("a", 1f).containsEntry("b", 2f);
        assertThat(embeddings.get()).isEqualTo(2);
        assertThat(accumulated.rankings).hasSize(4);
        assertThat(first.parents().getFirst().bestRrfScore()).isCloseTo(4.0 / 61, within(1e-9));
        assertThat(repeated.parents().getFirst().bestRrfScore())
                .isEqualTo(first.parents().getFirst().bestRrfScore());
    }

    @Test
    void bm25SkipsEmbeddingAndVectorFailureIsExplicit() {
        var embeds = new AtomicInteger();
        ChildRecallPort empty = (q, v, f, k) -> List.of();
        var retrieval =
                new HybridRetrievalOrchestrator(
                        texts -> {
                            embeds.incrementAndGet();
                            throw new IllegalStateException();
                        },
                        new ConcurrentRecallService(empty, empty),
                        new ParentEvidenceResolver(Optional.empty()),
                        new ScopeVersionService(StandardTestProperties.nullProvider()),
                        budgets);
        assertThat(
                        retrieval
                                .retrieve(
                                        scope,
                                        new SearchArguments(
                                                List.of("q"), RetrievalStrategy.BM25, 20),
                                        new HybridRetrievalOrchestrator.Accumulation(),
                                        8,
                                        24000)
                                .parents())
                .isEmpty();
        assertThat(embeds.get()).isZero();
        assertThatThrownBy(
                        () ->
                                retrieval.retrieve(
                                        scope,
                                        new SearchArguments(
                                                List.of("q"), RetrievalStrategy.VECTOR, 20),
                                        new HybridRetrievalOrchestrator.Accumulation(),
                                        8,
                                        24000))
                .hasMessage("vector-unavailable");
    }
}
