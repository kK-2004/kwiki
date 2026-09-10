package com.kwiki.rag.retrieval;

import com.kwiki.rag.answer.EvidenceAssembler;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.wiki.access.AuthorizationScope;
import com.kwiki.wiki.access.ScopeVersionService;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA 门禁链路的检索契约（精确数值）：两个分支都
 * 命中同一个子分块时融合为 1/65 + 1/62，扩展只用全新的
 * 排名重新获取，上下文字符预算确定性地裁剪，且生命周期
 * 排除项故障关闭 / 对超级用户同样排除已归档 id。
 */
class QaRetrievalContractsTest {

    static ChunkHit hit(String key, String parent, String content) {
        return new ChunkHit(key, parent, 1L, "PAGE", 7L, 3L, "heading", 0, content.length(),
                content);
    }

    static class RecordingBranch implements ChildRecallPort {
        List<ChunkHit> hits = List.of();
        int lastTopK;

        @Override
        public List<ChunkHit> search(String effectiveQuery, float[] queryVector,
                                     ScopeFilter scopeFilter, int topK) {
            lastTopK = topK;
            return hits.stream().limit(topK).toList();
        }
    }

    private QaChildRetrievalService service(RecordingBranch bm25, RecordingBranch vector) {
        var versions = new ScopeVersionService(nullProvider());
        var recall = new ConcurrentRecallService(bm25, vector);
        var embeddings = new com.kwiki.indexing.pipeline.ChunkEmbeddingPort() {
            @Override
            public List<float[]> embed(List<String> texts) {
                return texts.stream().map(text -> new float[4]).toList();
            }

            @Override
            public String cacheIdentity() {
                return "test";
            }
        };
        return new QaChildRetrievalService(embeddings, recall, versions,
                new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 1000, 2000, 24000));
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> nullProvider() {
        return (org.springframework.beans.factory.ObjectProvider<T>)
                new org.springframework.beans.factory.ObjectProvider<Object>() {
                    @Override
                    public Object getIfAvailable() {
                        return null;
                    }
                };
    }

    private RunContext run() {
        return new RunContext("test",
                new AuthorizationScope(1, true, Set.of(), Map.of()),
                id -> 1L, com.kwiki.rag.orchestration.AgenticLimits.defaults(), Map.of());
    }

    @Test
    void sameChunkInBothBranchesFusesToOneCandidateWithExactRrfScore() {
        var bm25 = new RecordingBranch();
        var vector = new RecordingBranch();
        // 分块 A 在 BM25 上排第 5、在 VECTOR 上排第 2；填充项把列表补满
        bm25.hits = List.of(hit("B", "PB", "b"), hit("C", "PC", "c"), hit("D", "PD", "d"),
                hit("E", "PE", "e"), hit("A", "PA", "a"));
        vector.hits = List.of(hit("Z", "PZ", "z"), hit("A", "PA", "a"));
        var outcomes = service(bm25, vector)
                .retrieveChildren(run().scope, run(), "query",
                        new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 1000, 2000, 24000).base(),
                        new java.util.HashMap<>());

        var a = outcomes.children().stream()
                .filter(child -> child.chunkKey().equals("A")).findFirst().orElseThrow();
        double expected = 1.0 / (60 + 5) + 1.0 / (60 + 2);
        assertThat(a.rrfScore()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(a.bm25Rank()).isEqualTo(5);
        assertThat(a.vectorRank()).isEqualTo(2);
        assertThat(outcomes.children().stream().filter(child -> child.chunkKey().equals("A")))
                .hasSize(1); // 按 chunkKey 去重
    }

    @Test
    void expansionRunsAtWiderTopKAndRankingsAreNotCarriedOver() {
        var bm25 = new RecordingBranch();
        var vector = new RecordingBranch();
        bm25.hits = List.of(hit("A", "PA", "a"), hit("B", "PB", "b"));
        vector.hits = List.of(hit("B", "PB", "b"), hit("A", "PA", "a"));
        var retrieval = service(bm25, vector);
        var budgets = new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 1000, 2000, 24000);
        var cache = new java.util.HashMap<String, float[]>();

        retrieval.retrieveChildren(run().scope, run(), "query", budgets.base(), cache);
        var expanded = retrieval.retrieveChildren(run().scope, run(), "query",
                budgets.expanded(), cache);

        assertThat(bm25.lastTopK).isEqualTo(50);
        assertThat(expanded.fusedCandidateCount()).isEqualTo(2);
        // 每次调用只融合自己的排名；分数等于单次调用融合的结果
        var b = expanded.children().stream()
                .filter(child -> child.chunkKey().equals("B")).findFirst().orElseThrow();
        assertThat(b.rrfScore()).isCloseTo(1.0 / 61 + 1.0 / 62,
                org.assertj.core.data.Offset.offset(1e-12));
        // 向量嵌入在同一次运行内复用：单一身份，不重复嵌入
        assertThat(cache).containsKey("query");
    }

    @Test
    void finalTopKTrimsTheFusedListToTheStageLimit() {
        var bm25 = new RecordingBranch();
        var vector = new RecordingBranch();
        bm25.hits = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> hit("K" + i, "P" + i, "c" + i)).toList();
        vector.hits = List.of();
        var outcomes = service(bm25, vector)
                .retrieveChildren(run().scope, run(), "query",
                        new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 1000, 2000, 24000).base(),
                        new java.util.HashMap<>());

        assertThat(outcomes.fusedCandidateCount()).isEqualTo(20);
        assertThat(outcomes.children()).hasSize(8); // 基础最终 TopK
        assertThat(outcomes.children().get(0).chunkKey()).isEqualTo("K1"); // 排名顺序
    }

    @Test
    void degradedBranchIsReportedButSingleBranchStillFuses() {
        var bm25 = new RecordingBranch() {
            @Override
            public List<ChunkHit> search(String effectiveQuery, float[] queryVector,
                                         ScopeFilter scopeFilter, int topK) {
                throw new IllegalStateException("es shard gone");
            }
        };
        var vector = new RecordingBranch();
        vector.hits = List.of(hit("A", "PA", "a"));
        var outcomes = service(bm25, vector)
                .retrieveChildren(run().scope, run(), "query",
                        new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 1000, 2000, 24000).base(),
                        new java.util.HashMap<>());

        assertThat(outcomes.degradations()).contains("bm25-degraded");
        assertThat(outcomes.children()).hasSize(1);
    }

    @Test
    void lifecycleFilterFailsClosedWhenTrustworthySetsCannotBeBuilt() {
        var jdbc = org.mockito.Mockito.mock(
                org.springframework.jdbc.core.JdbcOperations.class,
                invocation -> {
                    throw new IllegalStateException("db down");
                });
        @SuppressWarnings("unchecked")
        var provider = (org.springframework.beans.factory.ObjectProvider<org.springframework.jdbc.core.JdbcOperations>)
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(jdbc);
        var service = new RetrievalLifecycleService(provider, Duration.ofSeconds(5));
        assertThatThrownBy(service::exclusions)
                .isInstanceOf(RetrievalLifecycleService.LifecycleFilterException.class);
    }

    @Test
    void parentBudgetTrimsContextDeterministically() {
        var budgets = new RetrievalBudgets(50, 40, 8, 3, 24000, Duration.ofSeconds(5));
        var assembler = new EvidenceAssembler(budgets);
        var parentA = new ParentEvidenceChunk("PA", 1, "PAGE", 7, 3L, "h", "A".repeat(700), 0.5,
                List.of(hit("C1", "PA", "A")));
        var parentB = new ParentEvidenceChunk("PB", 1, "PAGE", 7, 3L, "h", "B".repeat(700), 0.4,
                List.of(hit("C2", "PB", "B")));

        var evidence = assembler.assemble(List.of(parentA, parentB), 1000);

        // 首次命中顺序；第二个父分块只能用到它所分得的剩余预算
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).truncated()).isFalse();
        assertThat(evidence.get(1).truncated()).isTrue();
        assertThat(evidence.get(0).body().length() + evidence.get(1).body().length())
                .isLessThanOrEqualTo(1000);
    }
}
