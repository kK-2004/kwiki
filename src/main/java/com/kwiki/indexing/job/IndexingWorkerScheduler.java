package com.kwiki.indexing.job;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Feature-flagged worker loop plus queue-depth gauge. The worker stays disabled
 * until operators enable it (design: Wiki first, then indexing, then Agentic).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "kwiki.indexing.worker.enabled", havingValue = "true")
public class IndexingWorkerScheduler {

    private final IndexingWorker worker;
    private final IndexingJobStore jobs;
    private final String owner;
    private final int batchSize;
    private final AtomicLong queueDepth = new AtomicLong();

    public IndexingWorkerScheduler(IndexingWorker worker,
                                   IndexingJobStore jobs,
                                   MeterRegistry metrics,
                                   @Value("${kwiki.indexing.worker.batch-size:20}") int batchSize) {
        this.worker = worker;
        this.jobs = jobs;
        this.owner = "kwiki-" + hostTag() + "-" + ProcessHandle.current().pid();
        this.batchSize = batchSize;
        Gauge.builder("kwiki_indexing_queue_depth", queueDepth, AtomicLong::doubleValue)
                .description("open indexing jobs (pending, retrying, or leased)")
                .register(metrics);
    }

    private static String hostTag() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    @Scheduled(fixedDelayString = "${kwiki.indexing.worker.poll-interval:5s}",
            initialDelayString = "${kwiki.indexing.worker.initial-delay:10s}")
    public void poll() {
        queueDepth.set(jobs.queueDepth());
        worker.runBatch(owner, batchSize);
    }

    @PreDestroy
    public void shutdown() {
        worker.cancel();
    }
}
