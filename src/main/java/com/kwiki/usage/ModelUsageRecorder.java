package com.kwiki.usage;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/** 用量事件的有界内存队列：入队绝不阻塞模型调用，满时丢弃并计数。 */
@Component
public class ModelUsageRecorder {

    public static final int CAPACITY = 50_000;

    private final ArrayBlockingQueue<UsageEvent> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();

    public ModelUsageRecorder(ObjectProvider<MeterRegistry> metrics) {
        MeterRegistry registry = metrics.getIfAvailable();
        if (registry != null) {
            registry.gauge("kwiki_model_usage_dropped_total", dropped);
            registry.gauge("kwiki_model_usage_queue_size", queue, ArrayBlockingQueue::size);
        }
    }

    public void record(UsageEvent event) {
        if (event == null) return;
        if (!queue.offer(event)) dropped.incrementAndGet();
    }

    public List<UsageEvent> drain() {
        List<UsageEvent> events = new ArrayList<>(queue.size());
        queue.drainTo(events);
        return events;
    }

    long dropped() {
        return dropped.get();
    }
}
