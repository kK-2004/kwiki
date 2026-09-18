package com.kwiki.indexing.version;

import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.search.ElasticsearchCapacityGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 管理端创建/编辑契约（任务 6.1）：支持性校验拒绝、容量预检失败
 * 关闭、变更开关关闭时拒绝、自动编号与编辑限制由底层服务保证、
 * 别名绝不因配置保存而变化。
 */
class SearchIndexAdminServiceTest {

    private final SearchIndexVersionRepository repository =
            Mockito.mock(SearchIndexVersionRepository.class);
    private final VersionedIndexingPipelineRegistry pipelines =
            Mockito.mock(VersionedIndexingPipelineRegistry.class);
    private final ElasticsearchCapacityGuard capacity =
            Mockito.mock(ElasticsearchCapacityGuard.class);
    private final Map<Integer, SearchIndexVersion> rows = new HashMap<>();
    private SearchIndexAdminService service;

    private static EditableIndexConfig config(int dims) {
        return new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default",
                "text-embedding-v4", dims, 1);
    }

    @BeforeEach
    void setUp() {
        ChunkMappingBuilder mappingBuilder = new ChunkMappingBuilder();
        SearchIndexVersionService core = new SearchIndexVersionService(
                com.kwiki.testutil.StandardTestProperties.providerOf(repository), mappingBuilder);
        IndexingProperties indexing = new IndexingProperties(null, null,
                new IndexingProperties.Rebuild(50, 2, 20),
                new IndexingProperties.Catchup(100, Duration.ofSeconds(30)),
                new IndexingProperties.Capacity(20, 8, Duration.ofSeconds(5)),
                new IndexingProperties.Management(true));
        service = new SearchIndexAdminService(core, pipelines, capacity, indexing,
                com.kwiki.testutil.StandardTestProperties.providerOf(repository));

        when(repository.findMaxVersionNumber()).thenAnswer(inv ->
                rows.keySet().stream().max(Integer::compare).orElse(null));
        when(repository.findByVersionNumber(any(Integer.class))).thenAnswer(inv ->
                Optional.ofNullable(rows.get(inv.getArgument(0, Integer.class))));
        when(repository.findByDeletedAtIsNullOrderByVersionNumberAsc()).thenAnswer(inv ->
                rows.values().stream()
                        .filter(v -> v.getDeletedAt() == null)
                        .sorted(java.util.Comparator.comparingInt(SearchIndexVersion::getVersionNumber))
                        .toList());
        when(repository.save(any(SearchIndexVersion.class))).thenAnswer(inv -> {
            SearchIndexVersion entity = inv.getArgument(0);
            rows.put(entity.getVersionNumber(), entity);
            return entity;
        });
        when(pipelines.supports(any(EditableIndexConfig.class))).thenReturn(true);
        when(pipelines.unsupportedReason(any())).thenReturn(Optional.empty());
        when(capacity.preflight(any(Integer.class)))
                .thenReturn(new ElasticsearchCapacityGuard.PreflightResult(true, null, 50));
    }

    @Test
    void createAllocatesTheNextNumberWithoutTouchingTheAlias() {
        rows.put(1, SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config(1024), "h"));

        SearchIndexVersion created = service.createVersion(config(2048));

        assertThat(created.getVersionNumber()).isEqualTo(2);
        assertThat(created.getPhysicalName()).isEqualTo("kwiki-chunks-v2");
        assertThat(created.getBuiltConfigRevision()).isNull(); // 待重建
        assertThat(created.isSelected()).isFalse();
    }

    @Test
    void unsupportedConfigurationIsRejectedWithoutPartialVersion() {
        when(pipelines.supports(any(EditableIndexConfig.class))).thenReturn(false);
        when(pipelines.unsupportedReason(any()))
                .thenReturn(Optional.of("parser version is not supported: kwiki-parse-9"));

        assertThatThrownBy(() -> service.createVersion(config(1024)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parser version is not supported");
        assertThat(rows).isEmpty();
    }

    @Test
    void capacityPreflightFailureIsFailClosed() {
        when(capacity.preflight(any(Integer.class)))
                .thenReturn(new ElasticsearchCapacityGuard.PreflightResult(false,
                        "storage headroom below 20% (min observed 9%)", null));

        assertThatThrownBy(() -> service.createVersion(config(1024)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capacity preflight failed")
                .hasMessageContaining("9%");
        assertThat(rows).isEmpty();
    }

    @Test
    void mutationsDisabledRejectsCreateAndEdit() {
        IndexingProperties readOnly = new IndexingProperties(null, null,
                new IndexingProperties.Rebuild(50, 2, 20),
                new IndexingProperties.Catchup(100, Duration.ofSeconds(30)),
                new IndexingProperties.Capacity(20, 8, Duration.ofSeconds(5)),
                new IndexingProperties.Management(false));
        SearchIndexAdminService disabled = new SearchIndexAdminService(
                new SearchIndexVersionService(
                        com.kwiki.testutil.StandardTestProperties.providerOf(repository),
                        new ChunkMappingBuilder()),
                pipelines, capacity, readOnly,
                com.kwiki.testutil.StandardTestProperties.providerOf(repository));

        assertThatThrownBy(() -> disabled.createVersion(config(1024)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled by configuration");
        assertThatThrownBy(() -> disabled.editVersion(1, config(2048)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled by configuration");
    }

    @Test
    void editKeepsTheNumberAndMarksPendingRebuild() {
        SearchIndexVersion offline = SearchIndexVersion.bootstrapped(
                2, "kwiki-chunks-v2", config(1024), "hash");
        offline.applySnapshot(IndexVersionStatusPolicy.disableWrites(
                IndexVersionStatusPolicy.unpublish(offline.toSnapshot(false, false))));
        rows.put(2, offline);

        SearchIndexVersion edited = service.editVersion(2, config(2048));

        assertThat(edited.getVersionNumber()).isEqualTo(2);
        assertThat(edited.getConfigRevision()).isEqualTo(2);
        assertThat(edited.toSnapshot(false, false).dirty()).isTrue();
    }

    @Test
    void displayStatusIsDerivedFromTheSnapshot() {
        rows.put(1, SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config(1024), "h"));

        assertThat(service.displayStatus(1)).contains(IndexDisplayStatus.PUBLISHED);
        assertThat(service.displayStatus(9)).isEmpty();
    }
}
