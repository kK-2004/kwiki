package com.kwiki.eval;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.rag.retrieval.ChildRecallPort;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.ConcurrentRecallService;
import com.kwiki.rag.retrieval.QaRetrievalBudgets;
import com.kwiki.rag.retrieval.ScopeFilter;
import com.kwiki.testutil.AgenticTestSupport.ScriptRecall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineRetrievalProbeTest {

    final QaRetrievalBudgets budgets = new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 12000, 24000, 24000);

    static List<ChunkHit> hits(String prefix, int count) {
        return IntStream.range(0, count).mapToObj(i -> new ChunkHit(prefix + i, "P" + prefix + i, 7, "PAGE",
                i, 1L, "heading", 0, 4, "内容" + prefix + i)).toList();
    }

    static ChunkEmbeddingPort embeddings(boolean fail) {
        return new ChunkEmbeddingPort() {
            @Override
            public List<float[]> embed(List<String> texts) {
                if (fail) throw new IllegalStateException("embedding down");
                return texts.stream().map(text -> new float[4]).toList();
            }

            @Override
            public String cacheIdentity() {
                return "test";
            }
        };
    }

    @Test
    void probesBaseAndExpandedBudgetsLikeTheQaLadder() {
        var bm25 = new ScriptRecall(ChildRecallPort.Branch.BM25);
        var vector = new ScriptRecall(ChildRecallPort.Branch.VECTOR);
        bm25.hits = hits("B", 60);
        vector.hits = hits("V", 60);
        var probe = new BaselineRetrievalProbe(embeddings(false), new ConcurrentRecallService(bm25, vector), budgets);

        var result = probe.probe("部署方式", new ScopeFilter(false, List.of(7L), List.of()));

        assertThat(bm25.topKs).containsExactlyInAnyOrder(20, 50);
        assertThat(result.bm25()).hasSize(50);
        assertThat(result.vector()).hasSize(50);
        assertThat(result.fused8()).hasSize(8);
        assertThat(result.fused20()).hasSize(20);
        assertThat(result.fused8().getFirst().chunkKey()).isIn("B0", "V0");
        assertThat(result.byLayer()).containsOnlyKeys(EvalLayer.BM25_50, EvalLayer.VECTOR_50,
                EvalLayer.FUSED_8, EvalLayer.FUSED_20);
        assertThat(result.degradations()).isEmpty();
    }

    @Test
    void embeddingFailureDegradesToBm25Only() {
        var bm25 = new ScriptRecall(ChildRecallPort.Branch.BM25);
        var vector = new ScriptRecall(ChildRecallPort.Branch.VECTOR);
        bm25.hits = hits("B", 30);
        vector.hits = hits("V", 30);
        var probe = new BaselineRetrievalProbe(embeddings(true), new ConcurrentRecallService(bm25, vector), budgets);

        var result = probe.probe("部署方式", new ScopeFilter(false, List.of(7L), List.of()));

        assertThat(result.bm25()).hasSize(30);
        assertThat(result.vector()).isEmpty();
        assertThat(vector.queries).isEmpty();
        assertThat(result.degradations()).contains("vector-unavailable");
    }
}
