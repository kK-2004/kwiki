package com.kwiki.rag.tool;

import static com.kwiki.rag.tool.AgentToolTestSupport.child;
import static com.kwiki.rag.tool.AgentToolTestSupport.hit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwiki.rag.answer.ParentEvidence;
import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.retrieval.ParentEvidenceChunk;

import org.junit.jupiter.api.Test;

import java.util.List;

class ExpandParentsToolTest {
    final AgentToolTestSupport support = new AgentToolTestSupport();
    final ExpandParentsTool tool = new ExpandParentsTool(
            support.harness.parentFetch, support.harness.budgets, AgenticLimits.defaults());

    void stubParent(String parentKey, String childKey) {
        support.harness.parents.byKey.put(parentKey, new ParentEvidenceChunk(
                parentKey, 1, "PAGE", 1, 1L, "heading", "完整父段落 " + parentKey, 0,
                List.of(hit(childKey, parentKey, "片段 " + childKey))));
    }

    EvidencePool poolWith(String... keys) {
        EvidencePool pool = new EvidencePool("q");
        double score = 0.5;
        for (String key : keys) {
            pool.mergeChildren(List.of(child(key, "P-" + key, "片段 " + key, score)), 12000);
            score -= 0.1;
        }
        return pool;
    }

    static AgentToolArguments.ExpandParents args(AgentToolArguments.Range range, String... keys) {
        return new AgentToolArguments.ExpandParents(List.of(keys), range);
    }

    @Test
    void expandsEveryPoolChildWhenNoKeysAreGiven() {
        stubParent("P-C1", "C1");
        EvidencePool pool = poolWith("C1");

        var outcome = tool.execute(args(AgentToolArguments.Range.BASE), support.context(support.run(), pool));

        assertThat(outcome.evidenceRefs()).containsExactly("P-C1");
        assertThat(outcome.stats()).containsEntry("parentCount", 1).containsEntry("addedCount", 1)
                .containsEntry("childCount", 1);
        assertThat(pool.parents()).extracting(ParentEvidence::parentChunkKey).containsExactly("P-C1");
        assertThat(pool.parentFetches()).isEqualTo(1);
        assertThat(support.harness.parents.requestedKeys).hasSize(1);
    }

    @Test
    void onlyTheSelectedChildrenAreExpanded() {
        stubParent("P-C1", "C1");
        stubParent("P-C2", "C2");
        EvidencePool pool = poolWith("C1", "C2");

        tool.execute(args(AgentToolArguments.Range.BASE, "C2"), support.context(support.run(), pool));

        assertThat(support.harness.parents.requestedKeys.getFirst()).containsExactly("P-C2");
        assertThat(pool.parents()).extracting(ParentEvidence::parentChunkKey).containsExactly("P-C2");
    }

    @Test
    void expandingTheSameParentsAgainIsSkippedWithADegradationMarker() {
        stubParent("P-C1", "C1");
        EvidencePool pool = poolWith("C1");
        var context = support.context(support.run(), pool);
        tool.execute(args(AgentToolArguments.Range.BASE), context);

        var second = tool.execute(args(AgentToolArguments.Range.EXPANDED), context);

        assertThat(second.evidenceRefs()).isEmpty();
        assertThat(second.degradations()).containsExactly("parent-fetch-skipped:no-new-parents");
        assertThat(second.stats()).containsEntry("addedCount", 0).containsEntry("parentCount", 1);
        assertThat(pool.parentFetches()).as("跳过也消耗一次父块预算").isEqualTo(2);
    }

    @Test
    void childrenWithoutParentsAreReportedAsSkipped() {
        EvidencePool pool = poolWith("C1"); // 未登记任何父块

        var outcome = tool.execute(args(AgentToolArguments.Range.BASE), support.context(support.run(), pool));

        assertThat(outcome.degradations()).containsExactly("parent-fetch-skipped:no-parents");
        assertThat(pool.parents()).isEmpty();
    }

    @Test
    void invalidReferencesAndMissingEvidenceAreToolFailures() {
        assertThatThrownBy(() -> tool.execute(args(AgentToolArguments.Range.BASE),
                support.context(support.run(), new EvidencePool("q"))))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("no-evidence");

        EvidencePool pool = poolWith("C1");
        assertThatThrownBy(() -> tool.execute(args(AgentToolArguments.Range.BASE, "NOPE"),
                support.context(support.run(), pool)))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("unknown-child-key");
        assertThat(pool.parentFetches()).as("参数无效不消耗预算").isZero();
    }

    @Test
    void parentFetchBudgetIsEnforced() {
        stubParent("P-C1", "C1");
        EvidencePool pool = poolWith("C1");
        for (int i = 0; i < 6; i++) {
            pool.useParentFetch();
        }

        assertThatThrownBy(() -> tool.execute(args(AgentToolArguments.Range.BASE),
                support.context(support.run(), pool)))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("parent-budget-exhausted");
        assertThat(support.harness.parents.requestedKeys).isEmpty();
    }

    @Test
    void foreignArgumentsAreRejected() {
        assertThatThrownBy(() -> tool.execute(new AgentToolArguments.GraphExpand(null),
                support.context(support.run(), poolWith("C1"))))
                .isInstanceOf(AgentToolExecutor.ToolFailure.class).hasMessage("invalid-arguments");
        assertThat(tool.name()).isEqualTo("expand_parents");
    }
}
