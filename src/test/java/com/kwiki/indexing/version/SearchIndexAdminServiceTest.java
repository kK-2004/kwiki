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
                "text-embedding-v4", dims, "kwiki-schema-1");
    }

    @BeforeEach
    void setUp() {
        ChunkMappingBuilder mappingBuilder = new ChunkMappingBuilder(com.kwiki.indexing.schema.SchemaTestFixtures.schemas());
        SearchIndexVersionService core = new SearchIndexVersionService(
                com.kwiki.testutil.StandardTestProperties.providerOf(repository), mappingBuilder);
        IndexingProperties indexing = new IndexingProperties(null, null,
                new IndexingProperties.Rebuild(50, 2, 20),
                new IndexingProperties.Catchup(100, Duration.ofSeconds(30)),
                new IndexingProperties.Capacity(20, 8, Duration.ofSeconds(5)),
                new IndexingProperties.Management(true));
        service = new SearchIndexAdminService(core, pipelines, capacity, indexing,
                com.kwiki.testutil.StandardTestProperties.providerOf(repository),
                com.kwiki.indexing.schema.SchemaTestFixtures.schemas());

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
    void 未注册字段格式在新建与编辑时均被拒绝() {
        EditableIndexConfig unknown = new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default",
                "text-embedding-v4", 1024, "unknown-schema");
        assertThatThrownBy(() -> service.createVersion(unknown)).hasMessage("字段格式 unknown-schema 未注册或不可用");
        assertThatThrownBy(() -> service.editVersion(2, unknown)).hasMessage("字段格式 unknown-schema 未注册或不可用");
        assertThat(rows).isEmpty();
        Mockito.verifyNoInteractions(capacity);
    }

    @Test
    void 不可用字段格式在新建与编辑时均被拒绝() {
        var schema = Mockito.mock(com.kwiki.indexing.schema.ChunkIndexSchema.class);
        when(schema.id()).thenReturn("offline-schema");
        when(schema.availability()).thenReturn(com.kwiki.indexing.plugin.PluginAvailability.unavailable("缺少插件配置"));
        var restricted = new SearchIndexAdminService(Mockito.mock(SearchIndexVersionService.class),
                pipelines, capacity, new IndexingProperties(null, null, null, null, null,
                new IndexingProperties.Management(true)),
                com.kwiki.testutil.StandardTestProperties.providerOf(repository),
                new com.kwiki.indexing.schema.ChunkIndexSchemaRegistry(List.of(schema)));
        var config = new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "model", 1024, "offline-schema");
        assertThatThrownBy(() -> restricted.createVersion(config)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("字段格式 offline-schema 未注册或不可用");
        assertThatThrownBy(() -> restricted.editVersion(2, config)).hasMessage("字段格式 offline-schema 未注册或不可用");
        Mockito.verifyNoInteractions(capacity);
    }

    @Test
    void 多模态解析器不能搭配缺少资源标识的字段格式() {
        var schema = Mockito.mock(com.kwiki.indexing.schema.ChunkIndexSchema.class);
        when(schema.id()).thenReturn("text-only");
        when(schema.availability()).thenReturn(com.kwiki.indexing.plugin.PluginAvailability.ok());
        when(schema.capabilities()).thenReturn(java.util.Set.of());
        var restricted = new SearchIndexAdminService(Mockito.mock(SearchIndexVersionService.class),
                pipelines, capacity, new IndexingProperties(null, null, null, null, null,
                new IndexingProperties.Management(true)),
                com.kwiki.testutil.StandardTestProperties.providerOf(repository),
                new com.kwiki.indexing.schema.ChunkIndexSchemaRegistry(List.of(schema)));
        var config = new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "model", 1024, "text-only");
        assertThatThrownBy(() -> restricted.createVersion(config)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("字段格式 text-only 不提供解析器 kwiki-parse-2 需要的能力 RESOURCE_IDS");
        assertThatThrownBy(() -> restricted.editVersion(2, config)).hasMessageContaining("RESOURCE_IDS");
        Mockito.verifyNoInteractions(capacity);
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
                        new ChunkMappingBuilder(com.kwiki.indexing.schema.SchemaTestFixtures.schemas())),
                pipelines, capacity, readOnly,
                com.kwiki.testutil.StandardTestProperties.providerOf(repository),
                com.kwiki.indexing.schema.SchemaTestFixtures.schemas());

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
        offline.applySnapshot(IndexVersionStatusPolicy.unpublish(offline.toSnapshot(false)));
        offline.endWriteSession();
        rows.put(2, offline);

        SearchIndexVersion edited = service.editVersion(2, config(2048));

        assertThat(edited.getVersionNumber()).isEqualTo(2);
        assertThat(edited.getConfigRevision()).isEqualTo(2);
        assertThat(edited.toSnapshot(false).dirty()).isTrue();
    }

    @Test
    void displayStatusIsDerivedFromTheSnapshot() {
        rows.put(1, SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config(1024), "h"));

        assertThat(service.displayStatus(1)).contains(IndexDisplayStatus.PUBLISHED);
        assertThat(service.displayStatus(9)).isEmpty();
    }
}
