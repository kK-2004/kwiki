package com.kwiki.indexing.job;

import com.kwiki.indexing.search.ElasticsearchIndexBootstrap;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 带功能开关的工作线程循环与队列深度指标。默认启用，运维方仍可通过
 * 配置关闭；消费前会等待首次 Elasticsearch 索引初始化完成。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "kwiki.indexing.worker.enabled", havingValue = "true")
public class IndexingWorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(IndexingWorkerScheduler.class);

    private final IndexingWorker worker;
    private final IndexingJobStore jobs;
    private final ObjectProvider<ElasticsearchIndexBootstrap> indexBootstrap;
    private final String owner;
    private final int batchSize;
    private final AtomicLong queueDepth = new AtomicLong();

    public IndexingWorkerScheduler(IndexingWorker worker,
                                   IndexingJobStore jobs,
                                   ObjectProvider<ElasticsearchIndexBootstrap> indexBootstrap,
                                   MeterRegistry metrics,
                                   @Value("${kwiki.indexing.worker.batch-size:20}") int batchSize) {
        this.worker = worker;
        this.jobs = jobs;
        this.indexBootstrap = indexBootstrap;
        this.owner = "kwiki-" + hostTag() + "-" + ProcessHandle.current().pid();
        this.batchSize = batchSize;
        Gauge.builder("kwiki_indexing_queue_depth", queueDepth, AtomicLong::doubleValue)
                .description("open indexing jobs (pending, retrying, or leased)")
                .register(metrics);
        log.info("Indexing worker enabled: owner={}, batchSize={}", owner, batchSize);
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
        ElasticsearchIndexBootstrap bootstrap = indexBootstrap.getIfAvailable();
        if (bootstrap != null && !bootstrap.isReady()) {
            log.debug("Indexing worker is waiting for Elasticsearch index bootstrap");
            return;
        }
        queueDepth.set(jobs.queueDepth());
        worker.runBatch(owner, batchSize);
    }

    @PreDestroy
    public void shutdown() {
        worker.cancel();
    }
}
