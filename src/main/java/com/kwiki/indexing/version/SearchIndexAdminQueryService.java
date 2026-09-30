package com.kwiki.indexing.version;

import com.kwiki.indexing.job.IndexingJobTargetStore;
import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 管理端只读投影；不暴露构建 manifest、凭据、原文或堆栈。 */
@Service
public class SearchIndexAdminQueryService {
    private final SearchIndexVersionRepository versions;
    private final SearchIndexRebuildRunRepository runs;
    private final SearchIndexRebuildRangeRepository ranges;
    private final SearchIndexValidationReportRepository validations;
    private final SearchIndexAuditRepository audits;
    private final IndexVersionRetentionAdvisor retention;
    private final ElasticsearchIndexManager indexes;
    private final IndexingJobTargetStore targetStore;
    private final MeterRegistry metrics;
    private final MultimodalSwitchReadiness multimodalReadiness;

    public SearchIndexAdminQueryService(SearchIndexVersionRepository versions,
            SearchIndexRebuildRunRepository runs, SearchIndexRebuildRangeRepository ranges,
            SearchIndexValidationReportRepository validations, SearchIndexAuditRepository audits,
            IndexVersionRetentionAdvisor retention, ElasticsearchIndexManager indexes,
            IndexingJobTargetStore targetStore, MeterRegistry metrics,
            MultimodalSwitchReadiness multimodalReadiness) {
        this.versions=versions; this.runs=runs; this.ranges=ranges;
        this.validations=validations; this.audits=audits; this.retention=retention;
        this.indexes=indexes; this.targetStore=targetStore; this.metrics=metrics;
        this.multimodalReadiness=multimodalReadiness;
    }

    /** 灰度版本的知识库范围；未注入（离线测试）时视为全部为全局版本。 */
    private com.kwiki.indexing.gray.IndexVersionKbScope kbScope;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKbScope(com.kwiki.indexing.gray.IndexVersionKbScope kbScope) {
        this.kbScope = kbScope;
    }

    public MultimodalReadinessView multimodalReadiness() {
        return new MultimodalReadinessView(multimodalReadiness.ready(),
                multimodalReadiness.missingConfiguration());
    }

    public List<VersionView> versions() {
        Set<Integer> candidates = retention.recommend().versions().stream()
                .filter(IndexVersionRetentionAdvisor.VersionRecommendation::cleanupCandidate)
                .map(IndexVersionRetentionAdvisor.VersionRecommendation::versionNumber)
                .collect(java.util.stream.Collectors.toSet());
        return versions.findByDeletedAtIsNullOrderByVersionNumberAsc().stream()
                .map(version -> view(version, candidates.contains(version.getVersionNumber())))
                .toList();
    }

    public AliasTruth aliasTruth() {
        try { return new AliasTruth(ElasticsearchIndexManager.ALIAS, indexes.aliasTargets()); }
        catch (Exception failure) { throw new IllegalStateException("alias truth is unavailable"); }
    }

    public List<VersionView> writableTargets() {
        return versions.findByWriteEnabledTrueAndDeletedAtIsNull().stream()
                .map(version -> view(version, false)).toList();
    }

    public List<RunView> runs() {
        return runs.findAll(Sort.by(Sort.Direction.DESC, "id")).stream().map(run ->
                new RunView(run.getId(), run.getVersionNumber(), run.getBuildGeneration(),
                        run.kind().name(), run.state().name(), run.switchState().name(),
                        run.getConfigRevision(), run.getBuildStartEventId(), run.getReplayEventId(),
                        run.getDualWriteStartEventId(), run.getCatchupBarrierEventId(),
                        run.getResourcesScanned(), run.getResourcesSucceeded(),
                        run.getResourcesSkipped(), run.getResourcesFailed(),
                        run.getRequestedBy(), run.getStartedAt(), run.getCompletedAt(),
                        run.getErrorClass(), run.getErrorSummary(), ranges(run.getId()))).toList();
    }

    public List<ValidationView> validations() {
        return validations.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
                .map(report -> new ValidationView(report.getId(), report.getVersionNumber(),
                        report.getRunId(), report.getConfigRevision(), report.getStatus(),
                        report.isRevisionValid(), report.isManifestValid(), report.isMappingValid(),
                        report.isVectorDimensionValid(), report.isRequiredFieldsValid(),
                        report.isCoverageValid(), report.getSourceResources(),
                        report.getMissingResources(), report.isIntegrityValid(),
                        report.getStaleDocuments(), report.getOrphanChildren(),
                        report.getMalformedVectors(), report.getMixedManifestDocuments(),
                        report.isSynchronizationValid(), report.isSmokeQueriesValid(),
                        report.getBarrierEventId(), report.getSummary(), report.getCreatedAt()))
                .toList();
    }

    public List<AuditView> audits() {
        return audits.findAll(Sort.by(Sort.Direction.DESC, "id")).stream().map(audit ->
                new AuditView(audit.getId(), audit.getAction(), audit.getTargetVersion(),
                        audit.getRunId(), audit.getOperator(), audit.getConfigRevision(),
                        audit.getPriorState(), audit.getResultState(), audit.getOutcome(),
                        audit.getErrorSummary(), audit.getCreatedAt(), audit.getUpdatedAt())).toList();
    }

    public List<Map<String,Object>> writeStatistics() {
        return targetStore.targetStatsByVersion().stream().map(source->{
            Map<String,Object> row=new LinkedHashMap<>(source);
            Object raw=source.get("target_version");
            if(raw==null)raw=source.get("TARGET_VERSION");
            String version=String.valueOf(raw);
            double embeddingCalls=metrics.find("kwiki_indexing_embedding_calls_total")
                    .tag("targetVersion",version).counters().stream()
                    .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
            row.put("embedding_calls",embeddingCalls);
            return java.util.Collections.unmodifiableMap(row);
        }).toList();
    }

    private VersionView view(SearchIndexVersion version, boolean cleanupCandidate) {
        boolean activeRun = runs.existsByVersionNumberAndStateIn(version.getVersionNumber(),
                List.of(RebuildRunState.RUNNING.name(), RebuildRunState.PAUSED.name()));
        IndexVersionSnapshot snapshot=version.toSnapshot(activeRun);
        boolean multimodalBlocked = multimodalReadiness.isMultimodal(
                version.editableConfig().parserVersion()) && !multimodalReadiness.ready();
        // 灰度版本只服务范围内知识库，生命周期由灰度发布管理
        boolean scoped = kbScope != null && kbScope.isScoped(version.getVersionNumber());
        Actions actions = actionsFor(version, snapshot, activeRun, scoped,
                multimodalBlocked, cleanupCandidate);
        EditableIndexConfig config=version.editableConfig();
        return new VersionView(version.getVersionNumber(),version.getPhysicalName(),config,
                version.getConfigRevision(),version.getBuiltConfigRevision(),snapshot.dirty(),
                IndexVersionStatusPolicy.displayStatus(snapshot).name(),version.getBuildState(),
                version.getCatchupStatus(),version.isWriteEnabled(),version.isAdminDisabled(),
                version.isSelected(),version.isPipelineSupported(),version.getHealthSummary(),
                version.getNeedsAttentionReason(),version.getLastValidationAt(),
                version.getValidationSummary(),cleanupCandidate,actions.allowed(),scoped,
                version.getWriteEnabledEventId(),actions.migrateBlockedReason());
    }

    private List<RangeView> ranges(Long runId) { return runId==null?List.of():ranges
            .findByRunIdOrderByResourceType(runId).stream().map(range -> new RangeView(
                    range.getResourceType(),range.getMinId(),range.getMaxId(),range.getLastSeenId(),
                    range.getTailLastSeenId(),range.getTailMaxId(),range.getItemsScanned(),
                    range.getItemsSucceeded(),range.getItemsSkipped(),range.getItemsFailed())).toList(); }

    /** 全局页可执行动作：写入由管理员开关控制，迁移只能在写入开启后发起。 */
    static Actions actionsFor(SearchIndexVersion version, IndexVersionSnapshot snapshot,
                              boolean activeRun, boolean scoped, boolean multimodalBlocked,
                              boolean cleanupCandidate) {
        boolean writeEnabled = version.isWriteEnabled();
        boolean migrated = !snapshot.dirty() && snapshot.buildState() == IndexBuildState.BUILT
                && snapshot.catchupStatus() == IndexCatchupStatus.CURRENT;
        boolean idle = !activeRun;
        Map<String,Boolean> allowed = new LinkedHashMap<>();
        allowed.put("edit", !scoped && snapshot.editable());
        allowed.put("writeToggle", !scoped && idle && (writeEnabled
                ? !version.isSelected() : version.isPipelineSupported()));
        allowed.put("migrate", !scoped && idle && writeEnabled && !migrated
                && version.isPipelineSupported());
        allowed.put("validate", !scoped && idle && writeEnabled && migrated);
        allowed.put("select", !scoped && idle && !version.isSelected() && writeEnabled
                && migrated && !multimodalBlocked);
        allowed.put("delete", cleanupCandidate && idle && !writeEnabled);
        String blocked = !scoped && idle && !writeEnabled && !migrated ? "请先开启写入" : null;
        return new Actions(Map.copyOf(allowed), blocked);
    }

    record Actions(Map<String,Boolean> allowed, String migrateBlockedReason) {}

    public record AliasTruth(String alias,List<String> targets){}
    public record MultimodalReadinessView(boolean ready,List<String> missingConfiguration){}
    public record VersionView(int versionNumber,String physicalName,EditableIndexConfig configuration,
            long configRevision,Long builtConfigRevision,boolean dirty,String displayStatus,
            String buildState,String catchupStatus,boolean writeEnabled,boolean adminDisabled,
            boolean selected,boolean pipelineSupported,String healthSummary,String attentionReason,
            Instant lastValidationAt,String validationSummary,boolean cleanupCandidate,
            Map<String,Boolean> allowedActions,boolean kbScoped,
            Long writeEnabledEventId,String migrateBlockedReason){}
    public record RangeView(String resourceType,long minId,long maxId,long lastSeenId,
            long tailLastSeenId,Long tailMaxId,long scanned,long succeeded,long skipped,long failed){}
    public record RunView(Long runId,int versionNumber,long buildGeneration,String kind,String state,
            String switchState,long configRevision,long buildStartEventId,long replayEventId,
            Long dualWriteStartEventId,Long catchupBarrierEventId,long scanned,long succeeded,
            long skipped,long failed,String requestedBy,Instant startedAt,Instant completedAt,
            String errorClass,String errorSummary,List<RangeView> ranges){}
    public record ValidationView(Long id,int versionNumber,Long runId,long configRevision,String status,
            boolean revisionValid,boolean manifestValid,boolean mappingValid,boolean vectorDimensionValid,
            boolean requiredFieldsValid,boolean coverageValid,long sourceResources,long missingResources,
            boolean integrityValid,long staleDocuments,long orphanChildren,long malformedVectors,
            long mixedManifestDocuments,boolean synchronizationValid,boolean smokeQueriesValid,
            Long barrierEventId,String summary,Instant createdAt){}
    public record AuditView(Long id,String action,Integer targetVersion,Long runId,String operator,
            Long configRevision,String priorState,String resultState,String outcome,String errorSummary,
            Instant createdAt,Instant updatedAt){}
}
