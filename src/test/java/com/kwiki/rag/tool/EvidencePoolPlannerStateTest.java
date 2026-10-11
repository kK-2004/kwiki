package com.kwiki.rag.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.rag.answer.ParentEvidence;
import com.kwiki.rag.retrieval.ChildEvidence;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.routing.ClarifyRequest;

import org.junit.jupiter.api.Test;

import java.util.List;

class EvidencePoolPlannerStateTest {

    static ChildEvidence child(String key, double score) {
        return new ChildEvidence(key, "P-" + key, 1, "PAGE", 1, 1L, "h", 0, 2, "aa", 1, 1, score);
    }

    static ParentEvidence parent(String key, int order) {
        var hit = new ChunkHit("C-" + key, key, 1, "PAGE", 1, 1L, "h", 0, 2, "aa");
        return new ParentEvidence(key, "PAGE", 1, 1L, 1, "h", "正文 " + key, false, order, List.of(hit));
    }

    @Test
    void versionOnlyChangesWhenTheEvidenceActuallyChanges() {
        EvidencePool pool = new EvidencePool("q");
        assertThat(pool.version()).isZero();

        pool.mergeChildren(List.of(child("A", 0.1)), 1000);
        int afterFirst = pool.version();
        pool.mergeChildren(List.of(child("A", 0.1)), 1000);
        assertThat(afterFirst).isPositive();
        assertThat(pool.version()).as("相同内容再次合并不算变化").isEqualTo(afterFirst);

        pool.mergeChildren(List.of(child("A", 0.9)), 1000);
        assertThat(pool.version()).as("同一块分数提高算变化").isGreaterThan(afterFirst);

        int beforeParents = pool.version();
        pool.addParents(List.of(parent("P1", 0)));
        assertThat(pool.version()).isGreaterThan(beforeParents);
        int afterParents = pool.version();
        pool.addParents(List.of(parent("P1", 0)));
        assertThat(pool.version()).isEqualTo(afterParents);

        pool.applyRewrite("改写后的问题");
        pool.recordFailure("coverage-missing");
        assertThat(pool.version()).as("query 与失败记录不属于证据").isEqualTo(afterParents);
    }

    @Test
    void numberedParentsGivesEveryParentAUniqueCitationOrder() {
        EvidencePool pool = new EvidencePool("q");
        // 两次 expand_parents 各自从 0 编号
        pool.addParents(List.of(parent("P1", 0), parent("P2", 1)));
        pool.addParents(List.of(parent("P3", 0)));

        List<ParentEvidence> numbered = pool.numberedParents();

        assertThat(numbered).extracting(ParentEvidence::parentChunkKey).containsExactly("P1", "P2", "P3");
        assertThat(numbered).extracting(ParentEvidence::firstHitOrder).containsExactly(0, 1, 2);
        assertThat(numbered.get(2).matchedChildren()).hasSize(1);
        assertThat(numbered.get(2).body()).isEqualTo("正文 P3");
        assertThat(pool.parents()).extracting(ParentEvidence::firstHitOrder)
                .as("原始父块不被改动").containsExactly(0, 1, 0);
    }

    @Test
    void clarificationIsRecordedOncePerRun() {
        EvidencePool pool = new EvidencePool("q");
        assertThat(pool.clarification()).isNull();

        ClarifyRequest request = new ClarifyRequest("你想了解哪一个？", List.of(), true, "planner");
        pool.requestClarification(request);

        assertThat(pool.clarification()).isSameAs(request);
    }
}
