package com.kwiki.indexing.version;

import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/** 管理员手动重建：资格预检后进入与首次构建完全相同的版本级协调锁。 */
@Service
public class ManualIndexRebuildService {
    private final SearchIndexVersionRepository versions;
    private final VersionRebuildCoordinator coordinator;
    private final ElasticsearchIndexManager indexes;
    private final FixedRangeRebuildScanner scanner;
    private final IndexingProperties properties;
    private final GraphBuildRepository graphBuilds;

    public ManualIndexRebuildService(SearchIndexVersionRepository versions,
                                     VersionRebuildCoordinator coordinator,
                                     ElasticsearchIndexManager indexes,
                                     FixedRangeRebuildScanner scanner,
                                     IndexingProperties properties) {
        this(versions, coordinator, indexes, scanner, properties, null);
    }

    @Autowired
    public ManualIndexRebuildService(SearchIndexVersionRepository versions,
                                     VersionRebuildCoordinator coordinator,
                                     ElasticsearchIndexManager indexes,
                                     FixedRangeRebuildScanner scanner,
                                     IndexingProperties properties,
                                     @Nullable GraphBuildRepository graphBuilds) {
        this.versions = versions;
        this.coordinator = coordinator;
        this.indexes = indexes;
        this.scanner = scanner;
        this.properties = properties;
        this.graphBuilds = graphBuilds;
    }

    public VersionRebuildCoordinator.StartResult rebuild(int versionNumber, String requestedBy) {
        requireMutationsEnabled();
        requireEligible(version(versionNumber), false);
        return coordinator.startManual(new VersionRebuildCoordinator.Request(
                versionNumber, RebuildRunKind.MANUAL, requestedBy), run -> {
            SearchIndexVersion admitted = version(run.getVersionNumber());
            requireEligible(admitted, true);
            if (admitted.getConfigRevision() != run.getConfigRevision()) {
                throw new IllegalStateException("version configuration changed after run admission");
            }
            if (admitted.editableConfig().mappingSchemaVersion() >= 3) {
                indexes.recreateOfflineVersion(admitted.getPhysicalName(),
                        admitted.editableConfig().embeddingDimensions(),
                        admitted.editableConfig().mappingSchemaVersion());
            } else {
                indexes.recreateOfflineVersion(admitted.getPhysicalName(),
                        admitted.editableConfig().embeddingDimensions());
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
            throw new IllegalStateException("deleted version cannot be rebuilt");
        }
        if (version.isSelected()) {
            throw new IllegalStateException("switch away before rebuilding the selected version");
        }
        if (version.isWriteEnabled()) {
            throw new IllegalStateException("disable writes before rebuilding this version");
        }
        if (!version.isPipelineSupported()) {
            throw new IllegalStateException("version pipeline is unsupported by this deployment");
        }
        if (graphBuilds != null
                && graphBuilds.hasActiveRunReferencingChunkIndex(version.getVersionNumber())) {
            throw new IllegalStateException("version is referenced by an active graph build");
        }
        if (!admitted) {
            IndexVersionStatusPolicy.startBuild(version.toSnapshot(false, false));
        }
    }

    private void requireMutationsEnabled() {
        if (!Boolean.TRUE.equals(properties.management().mutationsEnabled())) {
            throw new IllegalStateException(SearchIndexAdminService.MUTATIONS_DISABLED_MESSAGE);
        }
    }
}
