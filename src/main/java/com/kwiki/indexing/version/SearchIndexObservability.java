package com.kwiki.indexing.version;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 索引管理命令的低基数指标与结构化日志边界。 */
@Component
public class SearchIndexObservability {
    private static final Logger log = LoggerFactory.getLogger(SearchIndexObservability.class);
    private final MeterRegistry metrics;

    public SearchIndexObservability(MeterRegistry metrics) { this.metrics=metrics; }

    public void command(String action,Integer version,Long runId,String result,long startedNanos){
        metrics.counter("kwiki_search_index_admin_commands_total","action",action,"result",result)
                .increment();
        Timer.builder("kwiki_search_index_admin_command_latency")
                .tag("action",action).tag("result",result).register(metrics)
                .record(Duration.ofNanos(System.nanoTime()-startedNanos));
        log.info("search-index command action={} version={} runId={} result={} latencyMs={}",
                action,version,runId,result,(System.nanoTime()-startedNanos)/1_000_000);
    }
}
