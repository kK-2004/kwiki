package com.kwiki.eval;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** 测试用内存上报端口。 */
class InMemoryReport implements EvalReportPort {

    boolean available = true;
    boolean failFlush;
    boolean failDelete;
    final List<String> deletedRuns = new CopyOnWriteArrayList<>();
    final List<String> deletedTraceIds = new CopyOnWriteArrayList<>();
    int flushes;
    final List<String> datasets = new CopyOnWriteArrayList<>();
    final List<String> itemIds = new CopyOnWriteArrayList<>();
    final List<TraceRecord> traces = new CopyOnWriteArrayList<>();
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
    public void recordTrace(TraceRecord trace) {
        traces.add(trace);
    }

    @Override
    public void flush() {
        flushes++;
        if (failFlush) throw new EvalReportException("boom");
    }

    @Override
    public void deleteRun(String datasetName, String runName, List<String> traceIds) {
        if (failDelete) throw new EvalReportException("langfuse down");
        deletedRuns.add(datasetName + "/" + runName);
        deletedTraceIds.addAll(traceIds);
    }
}
