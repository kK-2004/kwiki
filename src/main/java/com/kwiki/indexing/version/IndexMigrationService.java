package com.kwiki.indexing.version;

import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.indexing.config.IndexingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 存量迁移：写入开启后，按版本配置的解析器从原始数据（已发布修订、导入 PDF 源文件、
 * 附件原文件）重建写入会话起点之前的历史内容。写入未开启时一律拒绝——双写必须先于迁移。
 */
@Service
public class IndexMigrationService {
    private final SearchIndexVersionRepository versions;
    private final VersionRebuildCoordinator coordinator;
    private final FixedRangeRebuildScanner scanner;
    private final IndexingProperties properties;
    private final GraphBuildRepository graphBuilds;

    @Autowired
    public IndexMigrationService(SearchIndexVersionRepository versions,
                                 VersionRebuildCoordinator coordinator,
                                 FixedRangeRebuildScanner scanner,
                                 IndexingProperties properties,
                                 @Nullable GraphBuildRepository graphBuilds) {
        this.versions = versions;
        this.coordinator = coordinator;
        this.scanner = scanner;
        this.properties = properties;
        this.graphBuilds = graphBuilds;
    }

    public VersionRebuildCoordinator.StartResult migrate(int versionNumber, String requestedBy) {
        requireMutationsEnabled();
        requireEligible(version(versionNumber), false);
        return coordinator.startMigration(new VersionRebuildCoordinator.Request(
                versionNumber, RebuildRunKind.MIGRATION, requestedBy), run -> {
            SearchIndexVersion admitted = version(run.getVersionNumber());
            requireEligible(admitted, true);
            if (admitted.getConfigRevision() != run.getConfigRevision()) {
                throw new IllegalStateException("version configuration changed after run admission");
            }
            if (!Objects.equals(admitted.getWriteEnabledEventId(), run.getBuildStartEventId())) {
                throw new IllegalStateException("write session changed after run admission");
            }
            scanner.scan(run);
        });
    }

    private SearchIndexVersion version(int versionNumber) {
        return versions.findByVersionNumber(versionNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown index version: " + versionNumber));
    }

    private void requireEligible(SearchIndexVersion version, boolean admitted) {
        if (version.getDeletedAt() != null) {
            throw new IllegalStateException("deleted version cannot be migrated");
        }
        if (!version.isWriteEnabled() || version.getWriteEnabledEventId() == null) {
            throw new IllegalStateException("enable writes before migrating this version");
        }
        if (!version.isPipelineSupported()) {
            throw new IllegalStateException("version pipeline is unsupported by this deployment");
        }
        if (graphBuilds != null
                && graphBuilds.hasActiveRunReferencingChunkIndex(version.getVersionNumber())) {
            throw new IllegalStateException("version is referenced by an active graph build");
        }
        if (!admitted) {
            IndexVersionSnapshot snapshot = version.toSnapshot(false);
            if (!snapshot.dirty() && snapshot.buildState() == IndexBuildState.BUILT
                    && snapshot.catchupStatus() == IndexCatchupStatus.CURRENT) {
                throw new IllegalStateException("version is already migrated");
            }
            IndexVersionStatusPolicy.startMigration(snapshot);
        }
    }

    private void requireMutationsEnabled() {
        if (!Boolean.TRUE.equals(properties.management().mutationsEnabled())) {
            throw new IllegalStateException(SearchIndexAdminService.MUTATIONS_DISABLED_MESSAGE);
        }
    }
}
