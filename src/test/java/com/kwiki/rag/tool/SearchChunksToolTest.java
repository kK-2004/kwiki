package com.kwiki.rag.tool;

import static com.kwiki.rag.tool.AgentToolTestSupport.hit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.retrieval.ChildEvidence;
import com.kwiki.rag.retrieval.RetrievalStrategy;

import org.junit.jupiter.api.Test;

import java.util.List;

class SearchChunksToolTest {
    final AgentToolTestSupport support = new AgentToolTestSupport();
    final SearchChunksTool tool = new SearchChunksTool(
            support.harness.childRetrieval, support.harness.budgets, AgenticLimits.defaults());

    static AgentToolArguments.SearchChunks args(
            RetrievalStrategy strategy, AgentToolArguments.Range range, String... queries) {
        return new AgentToolArguments.SearchChunks(List.of(queries), strategy, range);
    }

    void corpus(String... keys) {
        var hits = java.util.Arrays.stream(keys).map(k -> hit(k, "P-" + k, "内容 " + k)).toList();
        support.harness.bm25.hits = hits;
        support.harness.vector.hits = hits;
    }

    @Test
    void hybridSearchAddsHitsToThePoolAndReportsWhatWasAdded() {
        corpus("C1", "C2");
        EvidencePool pool = new EvidencePool("部署");

        var outcome = tool.execute(args(RetrievalStrategy.HYBRID, AgentToolArguments.Range.BASE, "部署"),
                support.context(support.run(), pool));

        assertThat(outcome.evidenceRefs()).containsExactlyInAnyOrder("C1", "C2");
        assertThat(outcome.stats()).containsEntry("queryCount", 1)
                .containsEntry("addedCount", 2).containsEntry("childCount", 2);
        assertThat(pool.children()).extracting(ChildEvidence::chunkKey).containsExactlyInAnyOrder("C1", "C2");
        assertThat(pool.hybridRetrievals()).isEqualTo(1);
        assertThat(support.harness.bm25.queries).containsExactly("部署");
        assertThat(support.harness.vector.queries).containsExactly("部署");
    }

    @Test
    void strategyRestrictsWhichBranchesRun() {
        corpus("C1");

        tool.execute(args(RetrievalStrategy.BM25, AgentToolArguments.Range.BASE, "精确标识"),
                support.context(support.run(), new EvidencePool("q")));
        assertThat(support.harness.bm25.queries).containsExactly("精确标识");
        assertThat(support.harness.vector.queries).isEmpty();

        tool.execute(args(RetrievalStrategy.VECTOR, AgentToolArguments.Range.BASE, "语义问题"),
                support.context(support.run(), new EvidencePool("q")));
        assertThat(support.harness.bm25.queries).containsExactly("精确标识");
        assertThat(support.harness.vector.queries).containsExactly("语义问题");
    }

    @Test
    void rangeSelectsTheBaseOrExpandedBranchDepth() {
        corpus("C1");
        EvidencePool pool = new EvidencePool("q");
        var context = support.context(support.run(), pool);

        tool.execute(args(RetrievalStrategy.HYBRID, AgentToolArguments.Range.BASE, "q"), context);
        tool.execute(args(RetrievalStrategy.HYBRID, AgentToolArguments.Range.EXPANDED, "q"), context);

        assertThat(support.harness.bm25.topKs).containsExactly(20, 50);
        assertThat(pool.hybridRetrievals()).isEqualTo(2);
    }

    @Test
    void severalQueriesAreSearchedSeparatelyAndMergedWithoutDuplicates() {
        corpus("C1", "C2");
        EvidencePool pool = new EvidencePool("q");

        var outcome = tool.execute(args(RetrievalStrategy.HYBRID, AgentToolArguments.Range.BASE, "甲", "乙"),
                support.context(support.run(), pool));

        assertThat(support.harness.bm25.queries).containsExactly("甲", "乙");
        assertThat(pool.hybridRetrievals()).isEqualTo(2);
        assertThat(pool.children()).hasSize(2);
        assertThat(outcome.stats()).containsEntry("queryCount", 2).containsEntry("addedCount", 2);
    }

    @Test
    void secondSearchOnlyReportsNewChunks() {
        EvidencePool pool = new EvidencePool("q");
        var context = support.context(support.run(), pool);
        corpus("C1");
        tool.execute(args(RetrievalStrategy.HYBRID, AgentToolArguments.Range.BASE, "q"), context);
        corpus("C1", "C9");

        var outcome = tool.execute(args(RetrievalStrategy.HYBRID, AgentToolArguments.Range.BASE, "q2"), context);

        assertThat(outcome.evidenceRefs()).containsExactly("C9");
        assertThat(outcome.stats()).containsEntry("addedCount", 1).containsEntry("childCount", 2);
    }

    @Test
    void poolIsTrimmedToTheStageCharBudget() {
        String big = "字".repeat(5000);
        var hits = List.of(hit("C0", "P0", big), hit("C1", "P1", big), hit("C2", "P2", big), hit("C3", "P3", big));
        support.harness.bm25.hits = hits;
        support.harness.vector.hits = hits;
        EvidencePool pool = new EvidencePool("q");

        tool.execute(args(RetrievalStrategy.HYBRID, AgentToolArguments.Range.BASE, "q"),
                support.context(support.run(), pool));

        assertThat(pool.children()).as("base 子块字符预算 12000").hasSize(3);
    }

    @Test
    void emptyResultIsASuccessWithNothingAdded() {
        support.harness.bm25.hits = List.of();
        support.harness.vector.hits = List.of();

        var outcome = tool.execute(args(RetrievalStrategy.HYBRID, AgentToolArguments.Range.BASE, "q"),
                support.context(support.run(), new EvidencePool("q")));

        assertThat(outcome.evidenceRefs()).isEmpty();
        assertThat(outcome.stats()).containsEntry("addedCount", 0).containsEntry("childCount", 0);
    }

    @Test
    void retrievalBudgetIsCheckedUpFrontAndLeavesTheCounterUntouched() {
        corpus("C1");
        EvidencePool pool = new EvidencePool("q");
        for (int i = 0; i < 5; i++) {
            pool.useHybridRetrieval();
        }

        assertThatThrownBy(() -> tool.execute(
                args(RetrievalStrategy.HYBRID, AgentToolArguments.Range.BASE, "甲", "乙"),
                support.context(support.run(), pool)))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class)
                .hasMessage("retrieval-budget-exhausted");
        assertThat(pool.hybridRetrievals()).isEqualTo(5);
        assertThat(support.harness.bm25.queries).isEmpty();
    }

    @Test
    void foreignArgumentsAreRejected() {
        assertThatThrownBy(() -> tool.execute(new AgentToolArguments.RewriteQuery(
                AgentToolArguments.RewriteKind.FEEDBACK), support.context(support.run(), new EvidencePool("q"))))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class)
                .hasMessage("invalid-arguments");
        assertThat(tool.name()).isEqualTo("search_chunks");
    }
}
