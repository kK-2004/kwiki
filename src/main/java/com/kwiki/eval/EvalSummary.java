package com.kwiki.eval;

import java.util.Map;

/** 一次评测 run 的汇总；指标键为 {@link EvalLayer#metric()}。 */
public record EvalSummary(
        int total,
        int errors,
        int scored,
        Map<String, LayerMetric> overall,
        Map<String, Map<String, LayerMetric>> byCategory,
        Map<String, Map<String, LayerMetric>> byDomain,
        Map<String, Map<String, LayerMetric>> bySource,
        int negatives,
        double refusalAccuracy,
        Map<String, Map<String, Integer>> lossMatrix,
        int contextGains) {
}
