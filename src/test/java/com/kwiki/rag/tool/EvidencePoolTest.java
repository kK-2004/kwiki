package com.kwiki.rag.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.rag.answer.ParentEvidence;
import com.kwiki.rag.retrieval.ChildEvidence;

import org.junit.jupiter.api.Test;

import java.util.List;

class EvidencePoolTest {

    static ChildEvidence child(String key, String content, double score) {
        return new ChildEvidence(key, "P-" + key, 1, "PAGE", 1, 1L, "h", 0, content.length(), content, 1, 1, score);
    }

    static ParentEvidence parent(String key) {
        return new ParentEvidence(key, "PAGE", 1, 1L, 1, "h", "正文", false, 0, List.of());
    }

    @Test
    void mergeDeduplicatesByChunkKeyKeepingTheHigherScoreAndOrdersByScore() {
        EvidencePool pool = new EvidencePool("q");

        int first = pool.mergeChildren(List.of(child("A", "aa", 0.1), child("B", "bb", 0.3)), 1000);
        int second = pool.mergeChildren(List.of(child("A", "aa", 0.5), child("C", "cc", 0.2)), 1000);

        assertThat(first).isEqualTo(2);
        assertThat(second).as("A 已存在，只有 C 是新增").isEqualTo(1);
        assertThat(pool.children()).extracting(ChildEvidence::chunkKey).containsExactly("A", "B", "C");
        assertThat(pool.children().get(0).rrfScore()).isEqualTo(0.5);
        assertThat(pool.hasChildren()).isTrue();
        assertThat(pool.child("B")).isPresent();
        assertThat(pool.child("Z")).isEmpty();
    }

    @Test
    void charBudgetTrimsLowestScoredChildrenAndNeverShrinksBelowThePreviousBudget() {
        EvidencePool pool = new EvidencePool("q");
        String big = "字".repeat(5000);

        pool.mergeChildren(List.of(child("A", big, 0.4), child("B", big, 0.3), child("C", big, 0.2),
                child("D", big, 0.1)), 12000);
        assertThat(pool.children()).extracting(ChildEvidence::chunkKey)
                .as("与工作流 trimToBudget 一致：剩余额度 > 0 时才继续加入").containsExactly("A", "B", "C");

        // 更小的预算不会把已有结果挤掉
        pool.mergeChildren(List.of(child("E", "e", 0.05)), 1000);
        assertThat(pool.children()).extracting(ChildEvidence::chunkKey).contains("A", "B", "C");

        // 更大的预算会放开
        pool.mergeChildren(List.of(child("D", big, 0.1)), 40000);
        assertThat(pool.childKeys()).contains("D");
    }

    @Test
    void replaceChildrenUsesTheRetainedBudgetAndCountsNewKeys() {
        EvidencePool pool = new EvidencePool("q");
        pool.mergeChildren(List.of(child("A", "aa", 0.4)), 1000);

        int added = pool.replaceChildren(List.of(child("A", "aa", 0.4), child("G", "gg", 0.2)));

        assertThat(added).isEqualTo(1);
        assertThat(pool.childKeys()).containsExactlyInAnyOrder("A", "G");
    }

    @Test
    void parentsAreDeduplicatedAndTrackedAsUsed() {
        EvidencePool pool = new EvidencePool("q");

        assertThat(pool.addParents(List.of(parent("P1"), parent("P2")))).isEqualTo(2);
        assertThat(pool.addParents(List.of(parent("P2"), parent("P3")))).isEqualTo(1);

        assertThat(pool.parents()).extracting(ParentEvidence::parentChunkKey).containsExactly("P1", "P2", "P3");
        assertThat(pool.usedParentKeys()).containsExactlyInAnyOrder("P1", "P2", "P3");
    }

    @Test
    void queryStateAndCountersAreTrackedPerRun() {
        EvidencePool pool = new EvidencePool("原始问题");
        assertThat(pool.currentQuery()).isEqualTo("原始问题");
        assertThat(pool.latestReason()).isEmpty();

        pool.applyRewrite("改写一");
        pool.applyRewrite("改写二");
        pool.setRewriteSuggestions(List.of("子问题 1", "子问题 2"));
        pool.recordFailure("coverage-missing");
        pool.recordFailure("  ");
        pool.recordFailure(null);
        pool.useHybridRetrieval();
        pool.useHybridRetrieval();
        pool.useParentFetch();
        pool.useRewrite();

        assertThat(pool.currentQuery()).isEqualTo("改写二");
        assertThat(pool.rewriteHistory()).containsExactly("改写一", "改写二");
        assertThat(pool.rewriteSuggestions()).containsExactly("子问题 1", "子问题 2");
        assertThat(pool.stageFailures()).containsExactly("coverage-missing");
        assertThat(pool.latestReason()).isEqualTo("coverage-missing");
        assertThat(pool.hybridRetrievals()).isEqualTo(2);
        assertThat(pool.parentFetches()).isEqualTo(1);
        assertThat(pool.rewritesUsed()).isEqualTo(1);
        assertThat(pool.summary()).containsEntry("hybridRetrievals", 2).containsEntry("rewrites", 2)
                .doesNotContainKey("currentQuery");
    }

    @Test
    void returnedCollectionsAreDefensiveCopies() {
        EvidencePool pool = new EvidencePool("q");
        pool.mergeChildren(List.of(child("A", "aa", 0.4)), 1000);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> pool.children().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        pool.childKeys().clear();
        assertThat(pool.hasChildren()).isTrue();
    }
}
