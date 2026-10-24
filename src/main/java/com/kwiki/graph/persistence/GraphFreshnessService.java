package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphAuthorizationGate;
import com.kwiki.graph.GraphSelectionScope;
import com.kwiki.graph.GraphSourceCoverage;
import com.kwiki.graph.GraphSourceCoverageService;
import com.kwiki.graph.GraphSourceInventoryPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端的每库图新鲜度视图：门禁档位（按管理员视角计算——superuser 覆盖、
 * 全库选择范围，不代表任意用户的结果）、待重建起止时间、下次可提交时间、
 * 最近一次自动重建的批次与结果。只输出数量、版本与档位，不含正文、标题或用户标识。
 */
@Service
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphFreshnessService {

    /** 每库新鲜度行；无发布快照的库 gateMode 为 NO_SNAPSHOT。 */
    public record KbFreshness(long kbId, String gateMode, String gateReason,
                              Integer chunkIndexVersion, Long activeSnapshotId,
                              Long snapshotContentEpoch, Long snapshotSecurityEpoch,
                              long currentContentEpoch, long currentSecurityEpoch,
                              long expectedSourceCount, long visibleSourceCount,
                              long selectedSourceCount, long retiredSourceCount,
                              java.time.Instant rebuildFirstDirtyAt, java.time.Instant rebuildLastDirtyAt,
                              java.time.Instant rebuildNextEligibleAt, Boolean rebuildHardDelete,
                              Long rebuildLastBatchId, String rebuildLastOutcome) {
    }

    private final JdbcOperations jdbc;
    private final GraphBuildRepository repository;
    private final GraphSourceInventoryPort inventory;
    private final GraphRebuildRequestRepository rebuildRequests;
    private final com.kwiki.graph.config.GraphRuntimeSettings settings;
    /** 当前选中的 Chunk 版本来源；一库多个版本配对时只展示选中版本（与问答一致）。 */
    private final GraphBuildTargets targets;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public GraphFreshnessService(ObjectProvider<JdbcOperations> jdbc,
                                 GraphBuildRepository repository,
                                 GraphSourceInventoryPort inventory,
                                 GraphRebuildRequestRepository rebuildRequests,
                                 com.kwiki.graph.config.GraphRuntimeSettings settings,
                                 ObjectProvider<GraphBuildTargets> targets) {
        this(jdbc, repository, inventory, rebuildRequests, settings,
                targets == null ? null : targets.getIfAvailable(), Clock.systemUTC());
    }

    GraphFreshnessService(ObjectProvider<JdbcOperations> jdbc,
                          GraphBuildRepository repository,
                          GraphSourceInventoryPort inventory,
                          GraphRebuildRequestRepository rebuildRequests,
                          com.kwiki.graph.config.GraphRuntimeSettings settings,
                          GraphBuildTargets targets,
                          Clock clock) {
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
        this.repository = repository;
        this.inventory = inventory;
        this.rebuildRequests = rebuildRequests;
        this.settings = settings;
        this.targets = targets;
        this.clock = clock;
    }

    /** 全部知识库的新鲜度行，按 kbId 排序；仅含已有发布快照或待重建记录的库。 */
    public List<KbFreshness> freshness() {
        Map<Long, PublicationRow> publications = new LinkedHashMap<>();
        if (jdbc != null) {
            Integer selectedVersion = targets == null ? null
                    : targets.selectedVersion().map(version -> version.getVersionNumber()).orElse(null);
            // 一库可能有多个 Chunk 版本配对：优先当前选中版本，没有选中版本时取最高版本，
            // 不能按查询顺序互相覆盖
            jdbc.query("SELECT kb_id, chunk_index_version, active_snapshot_id "
                            + "FROM graph_publication WHERE active_snapshot_id IS NOT NULL",
                    rs -> {
                        PublicationRow row = new PublicationRow(rs.getLong(1), rs.getInt(2),
                                rs.getLong(3));
                        publications.merge(row.kbId(), row,
                                (kept, candidate) -> preferred(kept, candidate, selectedVersion));
                    });
        }
        Map<Long, GraphRebuildRequestRepository.GraphRebuildRequestRecord> requests =
                new LinkedHashMap<>();
        for (GraphRebuildRequestRepository.GraphRebuildRequestRecord record : rebuildRequests.findAll()) {
            requests.put(record.kbId(), record);
        }

        var config = settings.snapshot();
        Instant now = clock.instant();
        List<KbFreshness> rows = new ArrayList<>();
        var kbIds = new java.util.TreeSet<Long>();
        kbIds.addAll(publications.keySet());
        kbIds.addAll(requests.keySet());
        for (Long kbId : kbIds) {
            rows.add(freshness(kbId, publications.get(kbId), requests.get(kbId), config, now));
        }
        return rows;
    }

    private KbFreshness freshness(long kbId, PublicationRow publication,
                                  GraphRebuildRequestRepository.GraphRebuildRequestRecord request,
                                  com.kwiki.graph.config.GraphRuntimeSettings.Snapshot config,
                                  Instant now) {
        String gateMode = "NO_SNAPSHOT";
        String gateReason = null;
        Integer chunkVersion = null;
        Long snapshotId = null;
        Long snapshotContentEpoch = null;
        Long snapshotSecurityEpoch = null;
        long currentContent = 0;
        long currentSecurity = 0;
        long expected = 0;
        long visible = 0;
        long selected = 0;
        long retired = 0;
        long[] current = inventory.currentEpochs(kbId);
        currentContent = current[0];
        currentSecurity = current[1];
        if (publication != null) {
            chunkVersion = publication.chunkIndexVersion();
            snapshotId = publication.activeSnapshotId();
            var entry = repository.findSnapshotById(publication.activeSnapshotId()).orElse(null);
            if (entry != null) {
                GraphSourceCoverageService coverageService = new GraphSourceCoverageService(inventory);
                GraphSourceCoverage coverage = coverageService.prove(0L, true, kbId,
                        entry.snapshot(), repository.findManifestResourceIds(snapshotId),
                        GraphSelectionScope.all());
                GraphAuthorizationGate.Decision decision = new GraphAuthorizationGate()
                        .check(coverage, entry.snapshot(), publication.chunkIndexVersion());
                gateMode = decision.mode().name();
                gateReason = decision.reason();
                snapshotContentEpoch = entry.snapshot().contentEpoch();
                snapshotSecurityEpoch = entry.snapshot().securityEpoch();
                expected = coverage.expectedSourceCount();
                visible = coverage.visibleSourceCount();
                selected = coverage.selectedSourceCount();
                retired = coverage.retiredSourceCount();
            }
        }

        java.time.Instant firstDirty = null;
        java.time.Instant lastDirty = null;
        java.time.Instant nextEligible = null;
        Boolean hardDelete = null;
        Long lastBatchId = null;
        String lastOutcome = null;
        if (request != null) {
            firstDirty = request.firstDirtyAt();
            lastDirty = request.lastDirtyAt();
            hardDelete = request.hardDelete();
            lastBatchId = request.lastSubmittedBatchId();
            lastOutcome = request.lastOutcome();
            Instant eligible = request.lastDirtyAt().plus(config.rebuildQuietPeriod());
            if (request.nextAttemptAt() != null && request.nextAttemptAt().isAfter(eligible)) {
                eligible = request.nextAttemptAt();
            }
            if (request.hardDelete() && request.nextAttemptAt() == null) {
                eligible = now; // 硬删除跳过静默期，下一轮即可提交
            }
            nextEligible = eligible;
        }
        return new KbFreshness(kbId, gateMode, gateReason, chunkVersion, snapshotId,
                snapshotContentEpoch, snapshotSecurityEpoch, currentContent, currentSecurity,
                expected, visible, selected, retired,
                firstDirty, lastDirty, nextEligible, hardDelete, lastBatchId, lastOutcome);
    }

    record PublicationRow(long kbId, int chunkIndexVersion, long activeSnapshotId) {
    }

    /** 同一库两个配对取其一：选中版本优先，否则取较高的 Chunk 版本。 */
    static PublicationRow preferred(PublicationRow kept, PublicationRow candidate, Integer selectedVersion) {
        if (selectedVersion != null) {
            if (candidate.chunkIndexVersion() == selectedVersion) {
                return candidate;
            }
            if (kept.chunkIndexVersion() == selectedVersion) {
                return kept;
            }
        }
        return candidate.chunkIndexVersion() > kept.chunkIndexVersion() ? candidate : kept;
    }
}
