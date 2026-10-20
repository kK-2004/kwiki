package com.kwiki.eval;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LayerScorerTest {

    // 五个关键词组，分别设计成落在不同丢失层
    static final List<List<String>> KEYWORDS = List.of(
            List.of("甲"),   // 0：答案命中
            List.of("乙"),   // 1：上下文有、答案无 → GENERATION_MISS
            List.of("丙"),   // 2：哪里都没有 → RECALL_MISS
            List.of("丁"),   // 3：只在 BM25 池 → RANK_MISS_20
            List.of("戊"));  // 4：在 fused@20 不在 fused@8 → RANK_MISS_8

    static Map<EvalLayer, List<String>> segments() {
        Map<EvalLayer, List<String>> segments = new EnumMap<>(EvalLayer.class);
        segments.put(EvalLayer.BM25_50, List.of("甲", "丁"));
        segments.put(EvalLayer.VECTOR_50, List.of("戊"));
        segments.put(EvalLayer.FUSED_8, List.of("甲"));
        segments.put(EvalLayer.FUSED_20, List.of("甲", "戊"));
        segments.put(EvalLayer.CONTEXT, List.of("甲 乙"));
        segments.put(EvalLayer.ANSWER, List.of("答案提到甲"));
        return segments;
    }

    @Test
    void poolIsTheUnionOfBothBranches() {
        var score = LayerScorer.score(KEYWORDS, segments());
        assertThat(score.matched(EvalLayer.POOL_50)).containsExactly(0, 3, 4);
    }

    @Test
    void lossLayersFollowTheSpecOrder() {
        var score = LayerScorer.score(KEYWORDS, segments());
        assertThat(score.losses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                1, LossLayer.GENERATION_MISS,
                2, LossLayer.RECALL_MISS,
                3, LossLayer.RANK_MISS_20,
                4, LossLayer.RANK_MISS_8));
    }

    @Test
    void inFused8ButNotInContextIsContextMiss() {
        Map<EvalLayer, List<String>> segments = new EnumMap<>(EvalLayer.class);
        segments.put(EvalLayer.BM25_50, List.of("甲"));
        segments.put(EvalLayer.FUSED_8, List.of("甲"));
        segments.put(EvalLayer.FUSED_20, List.of("甲"));
        var score = LayerScorer.score(List.of(List.of("甲")), segments);
        assertThat(score.losses()).containsEntry(0, LossLayer.CONTEXT_MISS);
    }

    @Test
    void contextGainIsCountedWhenContextHasWhatThePoolMissed() {
        var score = LayerScorer.score(KEYWORDS, segments());
        assertThat(score.contextGains()).containsExactly(1);
    }

    @Test
    void coverageAndFullHit() {
        var score = LayerScorer.score(KEYWORDS, segments());
        assertThat(score.coverage(EvalLayer.POOL_50)).isEqualTo(0.6);
        assertThat(score.fullHit(EvalLayer.POOL_50)).isFalse();
        var full = LayerScorer.score(List.of(List.of("甲")), segments());
        assertThat(full.fullHit(EvalLayer.ANSWER)).isTrue();
    }
}
