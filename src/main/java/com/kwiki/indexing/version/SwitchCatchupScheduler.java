package com.kwiki.indexing.version;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期恢复所有已建立双写水位、尚未进入最终屏障的切换准备。 */
@Component
class SwitchCatchupScheduler {
    private static final Logger log = LoggerFactory.getLogger(SwitchCatchupScheduler.class);
    private final SearchIndexRebuildRunRepository runs;
    private final SwitchCatchupProcessor processor;
    private final SwitchPreparationBarrierService barriers;

    SwitchCatchupScheduler(SearchIndexRebuildRunRepository runs,
                           SwitchCatchupProcessor processor,
                           SwitchPreparationBarrierService barriers) {
        this.runs = runs;
        this.processor = processor;
        this.barriers = barriers;
    }

    @Scheduled(fixedDelayString = "${kwiki.indexing.catchup.scan-interval:30s}")
    void tick() {
        for (SearchIndexRebuildRun run : runs
                .findByStateAndSwitchStateOrderByIdAsc(
                        RebuildRunState.COMPLETED.name(), IndexSwitchState.PREPARING.name())) {
            try {
                processor.processAvailable(run.getId());
                barriers.advance(run.getId());
            } catch (RuntimeException failure) {
                log.warn("Switch catch-up batch failed: runId={}, version={}, errorClass={}",
                        run.getId(), run.getVersionNumber(),
                        failure.getClass().getSimpleName());
            }
        }
    }
}
