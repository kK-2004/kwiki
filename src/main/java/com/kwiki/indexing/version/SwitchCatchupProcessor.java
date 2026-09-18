package com.kwiki.indexing.version;

import com.kwiki.indexing.config.IndexingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 可恢复的切换补齐：每次只推进一个有界批次，任务入队与游标同事务提交。 */
@Service
@ConditionalOnBean(JdbcTemplate.class)
public class SwitchCatchupProcessor {

    private final SearchIndexRebuildRunRepository runs;
    private final SearchIndexRebuildRangeRepository ranges;
    private final SearchIndexVersionRepository versions;
    private final JdbcTemplate jdbc;
    private final RebuildTargetEnqueuer enqueuer;
    private final TransactionTemplate transactions;
    private final int batchSize;

    public SwitchCatchupProcessor(SearchIndexRebuildRunRepository runs,
                                  SearchIndexRebuildRangeRepository ranges,
                                  SearchIndexVersionRepository versions,
                                  JdbcTemplate jdbc, RebuildTargetEnqueuer enqueuer,
                                  PlatformTransactionManager transactionManager,
                                  IndexingProperties properties) {
        this.runs = runs;
        this.ranges = ranges;
        this.versions = versions;
        this.jdbc = jdbc;
        this.enqueuer = enqueuer;
        this.transactions = new TransactionTemplate(transactionManager);
        this.batchSize = properties.catchup().batchSize();
    }

    /** 推进每类尾扫各一批，并推进一批事件；可由调度器和恢复流程重复调用。 */
    public Progress processAvailable(long runId) {
        int tailRows = 0;
        for (SearchIndexRebuildRange range : ranges.findByRunIdOrderByResourceType(runId)) {
            if (!range.tailComplete()) {
                Integer processed = transactions.execute(ignored -> processTailBatch(
                        runId, range.id()));
                tailRows += processed == null ? 0 : processed;
            }
        }
        Integer events = transactions.execute(ignored -> processEventBatch(runId));
        SearchIndexRebuildRun current = runs.findById(runId).orElseThrow();
        boolean tailsComplete = ranges.findByRunIdOrderByResourceType(runId).stream()
                .allMatch(SearchIndexRebuildRange::tailComplete);
        return new Progress(tailRows, events == null ? 0 : events,
                tailsComplete, current.replayComplete());
    }

    private int processTailBatch(long runId, long rangeId) {
        SearchIndexRebuildRange range = ranges.findByIdForUpdate(rangeId).orElseThrow();
        if (range.tailComplete()) return 0;
        Target target = target(runId);
        List<ResourceRow> rows = switch (range.getResourceType()) {
            case "PAGE" -> tailPageRows(range);
            case "ATTACHMENT" -> tailAttachmentRows(range);
            default -> throw new IllegalStateException(
                    "unsupported rebuild resource type: " + range.getResourceType());
        };
        for (ResourceRow row : rows) {
            if ("PAGE".equals(range.getResourceType())) {
                enqueuer.enqueueTailPage(runId, target.version(), target.physicalName(),
                        row.id(), row.revisionId(), row.lifecycleVersion());
            } else {
                enqueuer.enqueueTailAttachment(runId, target.version(), target.physicalName(),
                        row.id());
            }
        }
        long cursor = rows.isEmpty() ? range.getTailMaxId() : rows.get(rows.size() - 1).id();
        range.recordTailBatch(cursor, rows.size());
        return rows.size();
    }

    private int processEventBatch(long runId) {
        SearchIndexRebuildRun run = runs.findByIdForUpdate(runId).orElseThrow();
        Long upper = run.getDualWriteStartEventId();
        if (upper == null) {
            throw new IllegalStateException("switch preparation has not started");
        }
        if (run.replayComplete()) return 0;
        SearchIndexVersion version = versions.findByVersionNumber(run.getVersionNumber())
                .orElseThrow(() -> new IllegalStateException("catch-up version disappeared"));
        List<RebuildTargetEnqueuer.ChangeEvent> events = jdbc.query("""
                SELECT id, resource_type, resource_id, revision_id, operation, lifecycle_version
                FROM search_index_change_event
                WHERE id>? AND id<=?
                ORDER BY id ASC LIMIT ?
                """, (rs, row) -> new RebuildTargetEnqueuer.ChangeEvent(
                        rs.getLong("id"), rs.getString("resource_type"),
                        rs.getLong("resource_id"), nullableLong(rs, "revision_id"),
                        rs.getString("operation"), rs.getLong("lifecycle_version")),
                run.getReplayEventId(), upper, batchSize);
        for (RebuildTargetEnqueuer.ChangeEvent event : events) {
            enqueuer.replayEvent(runId, version.getVersionNumber(),
                    version.getPhysicalName(), event);
        }
        long cursor = events.isEmpty() ? upper : events.get(events.size() - 1).id();
        run.advanceReplayCursor(cursor);
        return events.size();
    }

    private Target target(long runId) {
        SearchIndexRebuildRun run = runs.findById(runId).orElseThrow();
        if (run.getDualWriteStartEventId() == null) {
            throw new IllegalStateException("switch preparation has not started");
        }
        SearchIndexVersion version = versions.findByVersionNumber(run.getVersionNumber())
                .orElseThrow(() -> new IllegalStateException("catch-up version disappeared"));
        return new Target(version.getVersionNumber(), version.getPhysicalName());
    }

    private List<ResourceRow> tailPageRows(SearchIndexRebuildRange range) {
        return jdbc.query("""
                SELECT p.id, p.current_published_revision_id, p.lifecycle_version
                FROM wiki_page p JOIN knowledge_base k ON k.id=p.kb_id
                WHERE p.id>? AND p.id<=? AND p.node_type='PAGE' AND p.status='ACTIVE'
                  AND p.current_published_revision_id IS NOT NULL AND k.status='ACTIVE'
                ORDER BY p.id ASC LIMIT ?
                """, (rs, row) -> new ResourceRow(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                range.getTailLastSeenId(), range.getTailMaxId(), batchSize);
    }

    private List<ResourceRow> tailAttachmentRows(SearchIndexRebuildRange range) {
        return jdbc.query("""
                SELECT a.id FROM attachment a JOIN knowledge_base k ON k.id=a.kb_id
                WHERE a.id>? AND a.id<=? AND a.status='STORED' AND k.status='ACTIVE'
                  AND LOWER(a.content_type) IN ('image/png','image/jpeg','image/gif','image/webp')
                ORDER BY a.id ASC LIMIT ?
                """, (rs, row) -> new ResourceRow(rs.getLong(1), null, 0),
                range.getTailLastSeenId(), range.getTailMaxId(), batchSize);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record Progress(int tailRowsEnqueued, int eventsReplayed,
                           boolean tailsComplete, boolean eventReplayComplete) { }

    private record ResourceRow(long id, Long revisionId, long lifecycleVersion) { }
    private record Target(int version, String physicalName) { }
}
