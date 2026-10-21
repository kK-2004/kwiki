package com.kwiki.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把仓库 jsonl 按 tier 嵌套同步为三个 Langfuse 数据集；条目 id 为 {datasetName}:{caseId}，幂等。
 * 先列出已有条目，内容未变的跳过，只写新增或变更的条目，减少受限流约束的请求数。
 */
@Service
public class EvalDatasetSyncService {

    private static final Logger log = LoggerFactory.getLogger(EvalDatasetSyncService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** items 为三个数据集的条目总数，written 为实际写入数，skipped 为内容未变而跳过的数。 */
    public record SyncResult(int items, int written, int skipped) {
    }

    private final EvalDatasetLoader loader;
    private final EvalReportPort report;

    public EvalDatasetSyncService(EvalDatasetLoader loader, EvalReportPort report) {
        this.loader = loader;
        this.report = report;
    }

    public SyncResult sync() {
        if (!report.available()) throw new EvalException(503, "langfuse-not-configured");
        List<EvalCase> all = loader.load();
        int items = 0;
        int written = 0;
        for (EvalDatasetSize size : EvalDatasetSize.values()) {
            report.upsertDataset(size.datasetName(), "kwiki RAG 召回评测 " + size.label());
            Map<String, EvalReportPort.StoredItem> existing = existing(size);
            for (EvalCase evalCase : EvalDatasetLoader.select(all, size)) {
                items++;
                String id = itemId(size, evalCase);
                Map<String, Object> input = input(evalCase);
                Map<String, Object> expected = expected(evalCase);
                Map<String, Object> metadata = metadata(evalCase);
                if (unchanged(existing.get(id), input, expected, metadata)) continue;
                report.upsertItem(size.datasetName(), id, input, expected, metadata);
                written++;
            }
        }
        return new SyncResult(items, written, items - written);
    }

    /** 列出失败时退回全量写入：同步结果正确优先于省请求。 */
    private Map<String, EvalReportPort.StoredItem> existing(EvalDatasetSize size) {
        try {
            return report.listItems(size.datasetName());
        } catch (RuntimeException e) {
            log.warn("list langfuse dataset items failed dataset={} reason={}", size.datasetName(), e.getMessage());
            return Map.of();
        }
    }

    private static boolean unchanged(EvalReportPort.StoredItem stored, Map<String, Object> input,
                                     Map<String, Object> expected, Map<String, Object> metadata) {
        return stored != null
                && JSON.valueToTree(input).equals(stored.input())
                && JSON.valueToTree(expected).equals(stored.expectedOutput())
                && JSON.valueToTree(metadata).equals(stored.metadata());
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
