package com.kwiki.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 把单题结果聚合为分层、分组指标与丢失层矩阵。 */
public final class EvalSummaries {

    private EvalSummaries() {
    }

    public static EvalSummary summarize(List<CaseResult> results) {
        List<CaseResult> scored = results.stream()
                .filter(result -> !result.error() && result.score() != null).toList();
        List<CaseResult> negatives = results.stream()
                .filter(result -> !result.error() && result.evalCase().negative()).toList();
        int errors = (int) results.stream().filter(CaseResult::error).count();
        double refusal = negatives.isEmpty() ? 0.0
                : negatives.stream().filter(result -> Boolean.TRUE.equals(result.refusedCorrectly())).count()
                        / (double) negatives.size();
        Map<String, Map<String, Integer>> lossMatrix = new TreeMap<>();
        int gains = 0;
        for (CaseResult result : scored) {
            Map<String, Integer> row = lossMatrix.computeIfAbsent(
                    result.evalCase().category(), ignored -> new TreeMap<>());
            for (LossLayer loss : result.score().losses().values()) {
                row.merge(loss.name(), 1, Integer::sum);
            }
            gains += result.score().contextGains().size();
        }
        return new EvalSummary(results.size(), errors, scored.size(), metrics(scored),
                grouped(scored, result -> result.evalCase().category()),
                grouped(scored, result -> result.evalCase().domain()),
                grouped(scored, result -> result.evalCase().source()),
                negatives.size(), refusal, lossMatrix, gains);
    }

    private static Map<String, Map<String, LayerMetric>> grouped(
            List<CaseResult> scored, Function<CaseResult, String> key) {
        Map<String, List<CaseResult>> groups = scored.stream()
                .collect(Collectors.groupingBy(key, TreeMap::new, Collectors.toList()));
        Map<String, Map<String, LayerMetric>> result = new TreeMap<>();
        groups.forEach((name, members) -> result.put(name, metrics(members)));
        return result;
    }

    private static Map<String, LayerMetric> metrics(List<CaseResult> scored) {
        Map<String, LayerMetric> metrics = new LinkedHashMap<>();
        for (EvalLayer layer : EvalLayer.values()) {
            if (scored.isEmpty()) {
                metrics.put(layer.metric(), new LayerMetric(0, 0, 0));
                continue;
            }
            double coverage = scored.stream().mapToDouble(result -> result.score().coverage(layer))
                    .average().orElse(0);
            double fullHit = scored.stream().filter(result -> result.score().fullHit(layer)).count()
                    / (double) scored.size();
            metrics.put(layer.metric(), new LayerMetric(coverage, fullHit, scored.size()));
        }
        return metrics;
    }
}
