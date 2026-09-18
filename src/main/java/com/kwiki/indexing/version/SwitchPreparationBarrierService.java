package com.kwiki.indexing.version;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/** 固化最终事件屏障，并在屏障内全部目标操作收敛后把补齐标记为 READY。 */
@Service
@ConditionalOnBean(JdbcTemplate.class)
public class SwitchPreparationBarrierService {
    private final SearchIndexVersionRepository versions;
    private final SearchIndexRebuildRunRepository runs;
    private final SearchIndexRebuildRangeRepository ranges;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SwitchPreparationBarrierService(SearchIndexVersionRepository versions,
                                           SearchIndexRebuildRunRepository runs,
                                           SearchIndexRebuildRangeRepository ranges,
                                           JdbcTemplate jdbc,
                                           PlatformTransactionManager transactionManager) {
        this(versions, runs, ranges, jdbc, transactionManager, Clock.systemUTC());
    }

    SwitchPreparationBarrierService(SearchIndexVersionRepository versions,
                                    SearchIndexRebuildRunRepository runs,
                                    SearchIndexRebuildRangeRepository ranges,
                                    JdbcTemplate jdbc,
                                    PlatformTransactionManager transactionManager,
                                    Clock clock) {
        this.versions = versions;
        this.runs = runs;
        this.ranges = ranges;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 幂等推进：未扫完、存在积压时保持 PREPARING；全部完成才进入 READY。 */
    public BarrierResult advance(long runId) {
        BarrierResult captured = transactions.execute(ignored -> capture(runId));
        if (captured == null || captured.state() != IndexSwitchState.PREPARING
                || captured.barrierEventId() == null) {
            return captured;
        }
        BarrierResult settled = transactions.execute(ignored -> settle(runId));
        return settled == null ? captured : settled;
    }

    private BarrierResult capture(long runId) {
        SearchIndexRebuildRun observed = runs.findById(runId).orElseThrow();
        List<SearchIndexRebuildRange> observedRanges =
                ranges.findByRunIdOrderByResourceType(runId);
        if (observed.switchState() == IndexSwitchState.READY) return result(observed, 0);
        if (observed.switchState() != IndexSwitchState.PREPARING) {
            throw new IllegalStateException("run is not preparing a switch: " + runId);
        }
        if (!observed.replayComplete() || observedRanges.isEmpty()
                || observedRanges.stream().anyMatch(range -> !range.tailComplete())) {
            return result(observed, -1);
        }

        // 全部切换相关事务统一按“版本行 → run”加锁，避免反向等待。
        versions.findAllActiveForUpdate();
        SearchIndexRebuildRun run = runs.findByIdForUpdate(runId).orElseThrow();
        if (run.switchState() == IndexSwitchState.READY) return result(run, 0);
        if (run.switchState() != IndexSwitchState.PREPARING) {
            throw new IllegalStateException("run is not preparing a switch: " + runId);
        }
        List<SearchIndexRebuildRange> runRanges =
                ranges.findByRunIdOrderByResourceType(runId);
        if (!run.replayComplete() || runRanges.isEmpty()
                || runRanges.stream().anyMatch(range -> !range.tailComplete())) {
            return result(run, -1);
        }

        // 与实时入队的 FOR SHARE 对偶：等待边界前事务完整提交目标行，
        // 再读取 MAX(event.id)，之后事件将看到已经开启的多写集合。
        long barrier = scalar("SELECT COALESCE(MAX(id), 0) FROM search_index_change_event");
        run.captureCatchupBarrier(barrier, clock.instant());
        return result(run, unresolved(run));
    }

    private BarrierResult settle(long runId) {
        versions.findAllActiveForUpdate();
        SearchIndexRebuildRun run = runs.findByIdForUpdate(runId).orElseThrow();
        if (run.switchState() == IndexSwitchState.READY) return result(run, 0);
        long unresolved = unresolved(run);
        if (unresolved == 0) {
            SearchIndexVersion version = versions.findByVersionNumber(run.getVersionNumber())
                    .orElseThrow(() -> new IllegalStateException(
                            "switch target version disappeared"));
            if (!version.isWriteEnabled() || version.isAdminDisabled()
                    || !version.isPipelineSupported()) {
                throw new IllegalStateException("switch target is no longer an enabled pipeline");
            }
            version.applySnapshot(IndexVersionStatusPolicy.caughtUp(
                    version.toSnapshot(false, true)));
            run.markSwitchReady(clock.instant());
        }
        return result(run, unresolved);
    }

    private long unresolved(SearchIndexRebuildRun run) {
        if (run.getCatchupBarrierEventId() == null) return -1;
        long eventGaps = scalar("""
                SELECT COUNT(*) FROM search_index_change_event e
                WHERE e.id>? AND e.id<=?
                  AND NOT EXISTS (
                    SELECT 1 FROM indexing_job_target t
                    WHERE t.event_id=e.id AND t.target_version=? AND t.state='COMPLETED')
                """, run.getBuildStartEventId(), run.getCatchupBarrierEventId(),
                run.getVersionNumber());
        long tailBacklog = scalar("""
                SELECT COUNT(*) FROM indexing_job_target t
                JOIN indexing_job j ON j.id=t.job_id
                WHERE t.target_version=? AND j.idempotency_key LIKE ?
                  AND t.state<>'COMPLETED'
                """, run.getVersionNumber(), "CATCHUP:" + run.getId() + ":TAIL:%");
        return eventGaps + tailBacklog;
    }

    private BarrierResult result(SearchIndexRebuildRun run, long unresolved) {
        return new BarrierResult(run.getId(), run.switchState(),
                run.getCatchupBarrierEventId(), unresolved);
    }

    private long scalar(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    public record BarrierResult(long runId, IndexSwitchState state,
                                Long barrierEventId, long unresolvedOperations) { }
}
