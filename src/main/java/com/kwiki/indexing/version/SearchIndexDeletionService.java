package com.kwiki.indexing.version;

import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.lang.Nullable;

import java.util.List;

/** 物理索引人工清理入口；没有调度器，删除只能由显式管理命令触发。 */
@Service
public class SearchIndexDeletionService {
    private static final String ACTION = "DELETE_PHYSICAL_INDEX";
    private static final List<String> ACTIVE_RUN_STATES = List.of(
            RebuildRunState.RUNNING.name(), RebuildRunState.PAUSED.name());

    private final SearchIndexVersionRepository versions;
    private final SearchIndexRebuildRunRepository runs;
    private final SearchIndexIdempotencyRepository idempotency;
    private final SearchIndexAuditRepository audits;
    private final ElasticsearchIndexManager indexes;
    private final JdbcOperations jdbc;
    private final GraphBuildRepository graphBuilds;

    public SearchIndexDeletionService(SearchIndexVersionRepository versions,
                                      SearchIndexRebuildRunRepository runs,
                                      SearchIndexIdempotencyRepository idempotency,
                                      SearchIndexAuditRepository audits,
                                      ElasticsearchIndexManager indexes,
                                      JdbcOperations jdbc) {
        this(versions, runs, idempotency, audits, indexes, jdbc, null);
    }

    @Autowired
    public SearchIndexDeletionService(SearchIndexVersionRepository versions,
                                      SearchIndexRebuildRunRepository runs,
                                      SearchIndexIdempotencyRepository idempotency,
                                      SearchIndexAuditRepository audits,
                                      ElasticsearchIndexManager indexes,
                                      JdbcOperations jdbc,
                                      @Nullable GraphBuildRepository graphBuilds) {
        this.versions = versions;
        this.runs = runs;
        this.idempotency = idempotency;
        this.audits = audits;
        this.indexes = indexes;
        this.jdbc = jdbc;
        this.graphBuilds = graphBuilds;
    }

    @Transactional(noRollbackFor = IndexDeletionException.class)
    public DeletionResult delete(int versionNumber, String exactNameConfirmation,
                                 String idempotencyKey, String operator) {
        String key = requireText(idempotencyKey, "idempotency key", 200);
        String actor = requireText(operator, "operator", 100);
        var replay = idempotency.findById(key);
        if (replay.isPresent()) {
            SearchIndexIdempotency prior = replay.get();
            if (!ACTION.equals(prior.getAction()) || prior.getTargetVersion() != versionNumber
                    || !actor.equals(prior.getOperator())) {
                throw new IllegalArgumentException("idempotency key belongs to another command");
            }
            return new DeletionResult(versionNumber, prior.getResponseSummary(), true);
        }

        SearchIndexVersion version = versions.findByVersionNumberForUpdate(versionNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown index version: " + versionNumber));
        requireEligible(version, exactNameConfirmation);
        SearchIndexIdempotency request = idempotency.saveAndFlush(
                SearchIndexIdempotency.pending(key, ACTION, versionNumber, actor));
        SearchIndexAudit audit = audits.saveAndFlush(SearchIndexAudit.pending(ACTION,
                versionNumber, null, actor, version.getConfigRevision(), "DISABLED"));
        try {
            boolean acknowledged = indexes.deletePhysicalIndex(version.getPhysicalName());
            if (!acknowledged) throw new IllegalStateException("ES deletion was not acknowledged");
            version.tombstone();
            request.complete("SUCCESS:ACKNOWLEDGED");
            audit.succeed("DELETED:ACKNOWLEDGED");
            return new DeletionResult(versionNumber, request.getResponseSummary(), false);
        } catch (Exception failure) {
            String summary = sanitize(failure);
            request.complete("FAILURE:" + summary);
            audit.fail(summary);
            throw new IndexDeletionException(summary, failure);
        }
    }

    private void requireEligible(SearchIndexVersion version, String confirmation) {
        String expected = indexes.indexNameFor(version.getVersionNumber());
        if (!expected.equals(version.getPhysicalName())
                || !version.getPhysicalName().matches(ElasticsearchIndexManager.PHYSICAL_NAME_PATTERN)) {
            throw new IllegalStateException("physical index name is not exact for its version");
        }
        if (!version.getPhysicalName().equals(confirmation)) {
            throw new IllegalArgumentException("exact physical index name confirmation is required");
        }
        if (version.isSelected()) throw new IllegalStateException("selected version cannot be deleted");
        if (version.isWriteEnabled()) throw new IllegalStateException("write-enabled version cannot be deleted");
        if (!version.isAdminDisabled()) throw new IllegalStateException("version must be explicitly disabled before deletion");
        if (IndexBuildState.BUILDING.name().equals(version.getBuildState())
                || runs.existsByVersionNumberAndStateIn(version.getVersionNumber(), ACTIVE_RUN_STATES)
                || runs.existsByVersionNumberAndSwitchState(version.getVersionNumber(),
                IndexSwitchState.PREPARING.name())) {
            throw new IllegalStateException("version has an active rebuild or catch-up");
        }
        if (graphBuilds != null
                && graphBuilds.hasActiveRunReferencingChunkIndex(version.getVersionNumber())) {
            throw new IllegalStateException("version is referenced by an active graph build");
        }
        Long activeJobs = jdbc.queryForObject("""
                SELECT COUNT(*) FROM indexing_job_target
                WHERE target_version=? AND state IN ('PENDING','LEASED','RETRY_WAIT')
                """, Long.class, version.getVersionNumber());
        if (activeJobs != null && activeJobs > 0) {
            throw new IllegalStateException("version has active indexing jobs");
        }
    }

    private static String requireText(String value, String label, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalArgumentException(label + " is required and must be at most " + max);
        }
        return normalized;
    }

    private static String sanitize(Exception failure) {
        String message = failure.getMessage();
        String value = message == null ? failure.getClass().getSimpleName() : message;
        return value.substring(0, Math.min(value.length(), 500));
    }

    public record DeletionResult(int versionNumber, String outcome, boolean replayed) { }

    public static final class IndexDeletionException extends RuntimeException {
        IndexDeletionException(String message, Throwable cause) { super(message, cause); }
    }
}
