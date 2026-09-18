package com.kwiki.indexing.version;

import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** 持久化暂停/恢复/取消意图，worker 在批次边界协作响应。 */
@Service
@ConditionalOnBean(SearchIndexRebuildRunRepository.class)
public class RebuildRunControlService {
    private final SearchIndexRebuildRunRepository runs;
    private final Clock clock;

    public RebuildRunControlService(SearchIndexRebuildRunRepository runs) {
        this(runs, Clock.systemUTC());
    }

    RebuildRunControlService(SearchIndexRebuildRunRepository runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    @Transactional public void pause(long runId) { run(runId).requestPause(clock.instant()); }
    @Transactional public void resume(long runId) { run(runId).resume(clock.instant()); }
    @Transactional public void cancel(long runId) { run(runId).requestCancel(clock.instant()); }

    private SearchIndexRebuildRun run(long runId) {
        return runs.findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalArgumentException("unknown rebuild run: " + runId));
    }
}
