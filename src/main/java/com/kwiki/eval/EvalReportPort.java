package com.kwiki.eval;

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

    /** 缓冲一条 trace（达到批量阈值时自动发送）。 */
    void recordTrace(TraceRecord trace);

    /** 发送剩余缓冲；本 run 内有任何 trace 上传失败则抛 {@link EvalReportException}。 */
    void flush();

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

    record ScoreRecord(String name, double value, String comment) {
    }
}
