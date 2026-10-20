package com.kwiki.eval;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把仓库 jsonl 按 tier 嵌套同步为三个 Langfuse 数据集；条目 id 为 {datasetName}:{caseId}，幂等。 */
@Service
public class EvalDatasetSyncService {

    private final EvalDatasetLoader loader;
    private final EvalReportPort report;

    public EvalDatasetSyncService(EvalDatasetLoader loader, EvalReportPort report) {
        this.loader = loader;
        this.report = report;
    }

    public int sync() {
        if (!report.available()) throw new EvalException(503, "langfuse-not-configured");
        List<EvalCase> all = loader.load();
        int items = 0;
        for (EvalDatasetSize size : EvalDatasetSize.values()) {
            report.upsertDataset(size.datasetName(), "kwiki RAG 召回评测 " + size.label());
            for (EvalCase evalCase : EvalDatasetLoader.select(all, size)) {
                report.upsertItem(size.datasetName(), itemId(size, evalCase), input(evalCase),
                        expected(evalCase), metadata(evalCase));
                items++;
            }
        }
        return items;
    }

    static String itemId(EvalDatasetSize size, EvalCase evalCase) {
        return size.datasetName() + ":" + evalCase.id();
    }

    private static Map<String, Object> input(EvalCase evalCase) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", evalCase.query());
        input.put("category", evalCase.category());
        input.put("domain", evalCase.domain());
        return input;
    }

    private static Map<String, Object> expected(EvalCase evalCase) {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("keywords", evalCase.keywords());
        expected.put("negative", evalCase.negative());
        return expected;
    }

    private static Map<String, Object> metadata(EvalCase evalCase) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", evalCase.source());
        metadata.put("tier", evalCase.tier());
        metadata.put("sourceDocIds", evalCase.sourceDocIds());
        metadata.put("keypoints", evalCase.keypoints());
        return metadata;
    }
}
