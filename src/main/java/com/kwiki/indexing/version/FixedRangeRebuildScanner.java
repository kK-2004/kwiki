package com.kwiki.indexing.version;

import com.kwiki.indexing.config.IndexingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/** 按持久化 ID 上界扫描有效资源；每批游标、计数和目标任务在同一事务提交。 */
@Service
@ConditionalOnBean(JdbcTemplate.class)
public class FixedRangeRebuildScanner {
    private final SearchIndexRebuildRunRepository runs;
    private final SearchIndexRebuildRangeRepository ranges;
    private final SearchIndexVersionRepository versions;
    private final JdbcTemplate jdbc;
    private final RebuildTargetEnqueuer enqueuer;
    private final TransactionTemplate transactions;
    private final int batchSize;

    public FixedRangeRebuildScanner(SearchIndexRebuildRunRepository runs,
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
        this.batchSize = properties.rebuild().batchSize();
    }

    public void scan(SearchIndexRebuildRun run) {
        SearchIndexVersion version = versions.findByVersionNumber(run.getVersionNumber())
                .orElseThrow(() -> new IllegalStateException("rebuild version disappeared"));
        // 多模态解析代把 PDF 附件也纳入重建基线；parse-1 保持仅图片。
        boolean multimodalParser = com.kwiki.indexing.job.IndexingWorker.PARSER_VERSION_MULTIMODAL
                .equals(version.editableConfig().parserVersion());
        for (SearchIndexRebuildRange range : ranges.findByRunIdOrderByResourceType(run.getId())) {
            while (!range.baselineComplete()) {
                awaitRunnable(run.getId());
                long rangeId = range.id();
                transactions.executeWithoutResult(ignored -> processBatch(
                        run.getId(), rangeId, version.getVersionNumber(),
                        version.getPhysicalName(), multimodalParser));
                range = ranges.findById(rangeId).orElseThrow();
            }
        }
        awaitTargetCompletion(run.getId());
    }

    private void awaitRunnable(long runId) {
        while (true) {
            SearchIndexRebuildRun current = runs.findById(runId).orElseThrow();
            if (current.isCancelRequested() || current.state() == RebuildRunState.CANCELLED) {
                throw new RebuildCancelledException(runId);
            }
            if (!current.isPauseRequested() && current.state() != RebuildRunState.PAUSED) return;
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("rebuild pause wait interrupted", interrupted);
            }
        }
    }

    private void processBatch(long runId, long rangeId, int version, String physicalName,
                              boolean multimodalParser) {
        SearchIndexRebuildRange range = ranges.findByIdForUpdate(rangeId).orElseThrow();
        SearchIndexRebuildRun run = runs.findById(runId).orElseThrow();
        List<ResourceRow> rows = switch (range.getResourceType()) {
            case "PAGE" -> pageRows(range);
            case "ATTACHMENT" -> attachmentRows(range, multimodalParser);
            default -> throw new IllegalStateException(
                    "unsupported rebuild resource type: " + range.getResourceType());
        };
        for (ResourceRow row : rows) {
            if ("PAGE".equals(range.getResourceType())) {
                enqueuer.enqueuePage(runId, version, physicalName, row.id(),
                        row.revisionId(), row.lifecycleVersion());
            } else {
                enqueuer.enqueueAttachment(runId, version, physicalName, row.id());
            }
        }
        long cursor = rows.isEmpty() ? range.getMaxId() : rows.get(rows.size() - 1).id();
        range.recordBatch(cursor, rows.size(), 0, 0, 0);
        run.recordBatch(rows.size(), 0, 0, 0);
    }

    private void awaitTargetCompletion(long runId) {
        while (true) {
            Map<String, Long> counts = jdbc.query("""
                    SELECT t.state, COUNT(*) FROM indexing_job_target t
                    JOIN indexing_job j ON j.id=t.job_id
                    WHERE j.idempotency_key LIKE ? GROUP BY t.state
                    """, rs -> {
                Map<String, Long> result = new java.util.HashMap<>();
                while (rs.next()) result.put(rs.getString(1), rs.getLong(2));
                return result;
            }, "REBUILD:" + runId + ":%");
            long pending = counts.getOrDefault("PENDING", 0L)
                    + counts.getOrDefault("LEASED", 0L)
                    + counts.getOrDefault("RETRY_WAIT", 0L);
            if (pending == 0) {
                persistTargetResults(runId, counts.getOrDefault("COMPLETED", 0L),
                        counts.getOrDefault("FAILED", 0L));
                if (counts.getOrDefault("FAILED", 0L) > 0) {
                    throw new IllegalStateException("baseline rebuild has failed target jobs");
                }
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("baseline rebuild interrupted", interrupted);
            }
        }
    }

    private void persistTargetResults(long runId, long succeeded, long failed) {
        transactions.executeWithoutResult(ignored -> {
            SearchIndexRebuildRun run = runs.findById(runId).orElseThrow();
            run.setTargetResults(succeeded, failed);
            for (SearchIndexRebuildRange range : ranges.findByRunIdOrderByResourceType(runId)) {
                Map<String, Object> result = jdbc.queryForMap("""
                        SELECT
                          SUM(CASE WHEN t.state='COMPLETED' THEN 1 ELSE 0 END) succeeded,
                          SUM(CASE WHEN t.state='FAILED' THEN 1 ELSE 0 END) failed
                        FROM indexing_job_target t JOIN indexing_job j ON j.id=t.job_id
                        WHERE j.idempotency_key LIKE ?
                        """, "REBUILD:" + runId + ":" + range.getResourceType() + ":%");
                range.setTargetResults(number(result.get("succeeded")),
                        number(result.get("failed")));
            }
        });
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private List<ResourceRow> pageRows(SearchIndexRebuildRange range) {
        return jdbc.query("""
                SELECT p.id, p.current_published_revision_id, p.lifecycle_version
                FROM wiki_page p JOIN knowledge_base k ON k.id=p.kb_id
                WHERE p.id>? AND p.id<=? AND p.node_type='PAGE' AND p.status='ACTIVE'
                  AND p.current_published_revision_id IS NOT NULL AND k.status='ACTIVE'
                ORDER BY p.id ASC LIMIT ?
                """, (rs, n) -> new ResourceRow(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                range.getLastSeenId(), range.getMaxId(), batchSize);
    }

    private List<ResourceRow> attachmentRows(SearchIndexRebuildRange range,
                                             boolean multimodalParser) {
        // 多模态解析代把 application/pdf 纳入可索引附件基线
        String types = multimodalParser
                ? "('image/png','image/jpeg','image/gif','image/webp','application/pdf')"
                : "('image/png','image/jpeg','image/gif','image/webp')";
        return jdbc.query("""
                SELECT a.id FROM attachment a JOIN knowledge_base k ON k.id=a.kb_id
                WHERE a.id>? AND a.id<=? AND a.status='STORED' AND k.status='ACTIVE'
                  AND LOWER(a.content_type) IN %s
                ORDER BY a.id ASC LIMIT ?
                """.formatted(types), (rs, n) -> new ResourceRow(rs.getLong(1), null, 0),
                range.getLastSeenId(), range.getMaxId(), batchSize);
    }

    private record ResourceRow(long id, Long revisionId, long lifecycleVersion) { }
}
