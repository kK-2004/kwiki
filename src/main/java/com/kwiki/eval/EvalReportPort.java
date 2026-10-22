package com.kwiki.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 评测结果上报抽象；当前实现为 Langfuse，测试可替换为内存实现。 */
public interface EvalReportPort {

    /** 是否已配置凭据。 */
    boolean available();

    /** 创建数据集；已存在视为成功。 */
    void upsertDataset(String name, String description);

    /** 以 itemId 幂等写入数据集条目。 */
    void upsertItem(String datasetName, String itemId, Map<String, Object> input,
                    Map<String, Object> expectedOutput, Map<String, Object> metadata);

    /** 列出数据集中已有条目（itemId → 内容），用于跳过未变更条目；数据集不存在时返回空。 */
    Map<String, StoredItem> listItems(String datasetName);

    /**
     * 同步上报一批 trace（ingestion + dataset-run-items），返回上报成功的 traceId：
     * ingestion 失败或整批被拒返回空列表；某条 dataset-run-items 关联失败则该条不计入返回值，
     * 调用方据此标记 reportPartial 并在继续时补报（trace id 确定，重复上报只会覆盖同一条记录）。
     */
    List<String> upload(List<TraceRecord> batch);

    /** 删除一个 run 在上报端的数据：给定 trace 与 dataset run；失败抛 {@link EvalReportException}。 */
    void deleteRun(String datasetName, String runName, List<String> traceIds);

    record TraceRecord(
            String traceId,
            String name,
            Map<String, Object> input,
            Map<String, Object> output,
            Map<String, Object> metadata,
            List<String> tags,
            List<SpanRecord> spans,
            List<ScoreRecord> scores,
            String datasetItemId,
            String runName,
            Map<String, Object> runMetadata) {
    }

    record SpanRecord(String id, String name, Instant start, Instant end,
                      Map<String, Object> input, Map<String, Object> output) {
    }

    /** Langfuse 上已存在的数据集条目内容。 */
    record StoredItem(JsonNode input, JsonNode expectedOutput, JsonNode metadata) {
    }

    record ScoreRecord(String name, double value, String comment) {
    }
}
