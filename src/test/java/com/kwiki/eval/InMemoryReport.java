package com.kwiki.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 测试用内存上报端口：记录每批上传，可按 traceId 注入失败模拟部分成功。 */
class InMemoryReport implements EvalReportPort {

    boolean available = true;
    boolean failDelete;
    /** 整批上报失败（返回空列表），模拟 Langfuse 不可用。 */
    boolean failUpload;
    final List<String> deletedRuns = new CopyOnWriteArrayList<>();
    final List<String> deletedTraceIds = new CopyOnWriteArrayList<>();
    /** 每批 upload 的批大小，按调用顺序记录。 */
    final List<Integer> batches = new CopyOnWriteArrayList<>();
    final List<String> datasets = new CopyOnWriteArrayList<>();
    final List<String> itemIds = new CopyOnWriteArrayList<>();
    final List<TraceRecord> traces = new CopyOnWriteArrayList<>();
    /** 这些 traceId 上报失败（不计入返回值），用于模拟部分成功 / 整批失败。 */
    final Set<String> failTraceIds = ConcurrentHashMap.newKeySet();
    /** 预置的「Langfuse 上已存在」条目：datasetName → (itemId → 内容)。 */
    final Map<String, Map<String, StoredItem>> existing = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public void upsertDataset(String name, String description) {
        datasets.add(name);
    }

    @Override
    public void upsertItem(String datasetName, String itemId, Map<String, Object> input,
                           Map<String, Object> expectedOutput, Map<String, Object> metadata) {
        itemIds.add(itemId);
    }

    @Override
    public Map<String, StoredItem> listItems(String datasetName) {
        return existing.getOrDefault(datasetName, Map.of());
    }

    @Override
    public List<String> upload(List<TraceRecord> batch) {
        batches.add(batch.size());
        List<String> uploaded = new ArrayList<>();
        for (TraceRecord trace : batch) {
            traces.add(trace);
            if (!failUpload && !failTraceIds.contains(trace.traceId())) uploaded.add(trace.traceId());
        }
        return uploaded;
    }

    @Override
    public void deleteRun(String datasetName, String runName, List<String> traceIds) {
        if (failDelete) throw new EvalReportException("langfuse down");
        deletedRuns.add(datasetName + "/" + runName);
        deletedTraceIds.addAll(traceIds);
    }
}
