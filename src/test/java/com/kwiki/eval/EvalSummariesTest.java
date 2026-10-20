package com.kwiki.eval;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalSummariesTest {

    static EvalCase positive(String id, String category, String domain) {
        return new EvalCase(id, "dragonball", 50, category, domain, "问题" + id,
                List.of(List.of("甲"), List.of("乙")), false, List.of(1), List.of());
    }

    static EvalCase negative(String id) {
        return new EvalCase(id, "dragonball", 50, "无关无解问", "Finance", "无解" + id,
                List.of(), true, List.of(1), List.of());
    }

    static CaseResult scored(EvalCase evalCase, List<String> answer) {
        Map<EvalLayer, List<String>> segments = new EnumMap<>(EvalLayer.class);
        segments.put(EvalLayer.BM25_50, List.of("甲乙"));
        segments.put(EvalLayer.CONTEXT, List.of("甲乙"));
        segments.put(EvalLayer.ANSWER, answer);
        return new CaseResult(evalCase, LayerScorer.score(evalCase.keywords(), segments), null,
                "completed", String.join("", answer), List.of(), Map.of(), null, 10);
    }

    @Test
    void summarizesLayersRefusalsLossesAndErrors() {
        var results = List.of(
                scored(positive("a", "事实性问题", "Finance"), List.of("甲乙")),
                scored(positive("b", "多跳推理问题", "Law"), List.of("甲")),
                new CaseResult(negative("n"), null, true, "insufficient", "", List.of(), Map.of(), null, 5),
                new CaseResult(positive("e", "事实性问题", "Finance"), null, null, null, "", List.of(),
                        Map.of(), "timeout", 7));

        EvalSummary summary = EvalSummaries.summarize(results);

        assertThat(summary.total()).isEqualTo(4);
        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.scored()).isEqualTo(2);
        assertThat(summary.overall().get("answer")).isEqualTo(new LayerMetric(0.75, 0.5, 2));
        assertThat(summary.overall().get("pool@50")).isEqualTo(new LayerMetric(1.0, 1.0, 2));
        assertThat(summary.byCategory().get("多跳推理问题").get("answer"))
                .isEqualTo(new LayerMetric(0.5, 0.0, 1));
        assertThat(summary.byDomain()).containsKeys("Finance", "Law");
        assertThat(summary.bySource()).containsKey("dragonball");
        assertThat(summary.negatives()).isEqualTo(1);
        assertThat(summary.refusalAccuracy()).isEqualTo(1.0);
        assertThat(summary.lossMatrix().get("多跳推理问题")).containsEntry("GENERATION_MISS", 1);
        assertThat(summary.contextGains()).isZero();
    }

    @Test
    void emptyResultsProduceZeroMetrics() {
        var summary = EvalSummaries.summarize(List.of());
        assertThat(summary.overall().get("answer")).isEqualTo(new LayerMetric(0, 0, 0));
        assertThat(summary.refusalAccuracy()).isZero();
    }
}
