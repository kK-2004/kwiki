package com.kwiki.eval;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** 测试用内存上报端口。 */
class InMemoryReport implements EvalReportPort {

    boolean available = true;
    boolean failFlush;
    int flushes;
    final List<String> datasets = new CopyOnWriteArrayList<>();
    final List<String> itemIds = new CopyOnWriteArrayList<>();
    final List<TraceRecord> traces = new CopyOnWriteArrayList<>();

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
    public void recordTrace(TraceRecord trace) {
        traces.add(trace);
    }

    @Override
    public void flush() {
        flushes++;
        if (failFlush) throw new EvalReportException("boom");
    }
}
