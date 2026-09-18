package com.kwiki.indexing.search;

import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 启动引导契约（无真实 ES/DB）：首次部署创建并持久化 v1；已有别名
 * 安全收养；无法匹配的遗留状态记录 NEEDS_ATTENTION 且绝不动别名；
 * 元数据已存在时重启绝不创建/切换更高版本；ES 不可用失败关闭。
 */
class ElasticsearchIndexBootstrapTest {

    private static ExternalServicesProperties properties() {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter(
                        "https://content-center.internal", "token", null, null,
                        Duration.ofSeconds(10), Duration.ofSeconds(600)),
                new ExternalServicesProperties.Elasticsearch(null, null, null),
                new ExternalServicesProperties.AnswerLlm(
                        "https://llm.internal/v1", "key", "model", Duration.ofSeconds(10)),
                new ExternalServicesProperties.QwenEmbedding(
                        "https://embed.internal/v1", "key", "text-embedding-v4", 1024,
                        Duration.ofSeconds(30)),
                ExternalServicesProperties.unusedVisionModel());
    }

    private static EditableIndexConfig deployedConfig() {
        return new EditableIndexConfig(
                "kwiki-parse-1", "kwiki-chunk-1", "default", "text-embedding-v4", 1024, 1);
    }

    /** 内存语义的注册表 mock：只实现引导路径用到的派生查询。 */
    private static final class InMemoryRegistry {
        final Map<Integer, SearchIndexVersion> rows = new HashMap<>();
        int saves;
        final SearchIndexVersionRepository proxy = Mockito.mock(SearchIndexVersionRepository.class);

        InMemoryRegistry() {
            when(proxy.existsByDeletedAtIsNull()).thenAnswer(inv ->
                    rows.values().stream().anyMatch(v -> v.getDeletedAt() == null));
            when(proxy.findByVersionNumber(any(Integer.class))).thenAnswer(inv ->
                    Optional.ofNullable(rows.get(inv.getArgument(0, Integer.class))));
            when(proxy.findBySelectedTrue()).thenAnswer(inv ->
                    rows.values().stream()
                            .filter(v -> v.getDeletedAt() == null && v.isSelected())
                            .findFirst());
            when(proxy.save(any(SearchIndexVersion.class))).thenAnswer(inv -> {
                saves++;
                SearchIndexVersion entity = inv.getArgument(0);
                rows.put(entity.getVersionNumber(), entity);
                return entity;
            });
        }
    }

    @Test
    void firstDeploymentCreatesIndexPersistsMetadataAndMountsAlias() throws Exception {
        FakeIndexManager indexes = new FakeIndexManager(false, List.of(), null);
        InMemoryRegistry registry = new InMemoryRegistry();
        ElasticsearchIndexBootstrap bootstrap =
                new ElasticsearchIndexBootstrap(indexes, providerOf(registry.proxy),
                        new ChunkMappingBuilder(), properties(), 1);

        bootstrap.run(null);

        assertThat(indexes.calls).containsExactly(
                "available", "aliasExists", "create:kwiki-chunks-v1",
                "validate:kwiki-chunks-v1:1024", "alias:kwiki-chunks-v1");
        assertThat(bootstrap.isReady()).isTrue();
        assertThat(registry.saves).isEqualTo(1);

        SearchIndexVersion v1 = registry.rows.get(1);
        assertThat(v1.isSelected()).isTrue();
        assertThat(v1.isWriteEnabled()).isTrue();
        assertThat(v1.getBuiltConfigRevision()).isEqualTo(1L);
        assertThat(v1.getMappingHash()).hasSize(64);
        assertThat(v1.getNeedsAttentionReason()).isNull();
    }

    @Test
    void mappingHashMatchesTheRecordedV1BaselineReference() {
        // 当前基线（dims=1024，含 lifecycleVersion 与 contentIds）规范化 SHA-256。
        assertThat(new ChunkMappingBuilder().mappingHash(1024))
                .isEqualTo("8b7ca5b5c742fd946f32981b25d01cdb98f7ff412dd977efac5c9ab33f660965");
    }

    @Test
    void existingAliasWithMatchingMappingIsAdopted() throws Exception {
        FakeIndexManager indexes = new FakeIndexManager(true, List.of("kwiki-chunks-v1"), null);
        InMemoryRegistry registry = new InMemoryRegistry();
        ElasticsearchIndexBootstrap bootstrap =
                new ElasticsearchIndexBootstrap(indexes, providerOf(registry.proxy),
                        new ChunkMappingBuilder(), properties(), 1);

        bootstrap.run(null);

        assertThat(indexes.calls).containsExactly("available", "aliasExists",
                "targets", "validate:kwiki-chunks-v1:1024");
        assertThat(indexes.calls).doesNotContain("create:kwiki-chunks-v1", "alias:kwiki-chunks-v1");

        SearchIndexVersion adopted = registry.rows.get(1);
        assertThat(adopted.isSelected()).isTrue();
        assertThat(adopted.getBuiltConfigRevision()).isEqualTo(1L);
        assertThat(adopted.getNeedsAttentionReason()).isNull();
    }

    @Test
    void unmatchedLegacyAliasIsRecordedNeedsAttentionWithoutTouchingReads() throws Exception {
        FakeIndexManager indexes = new FakeIndexManager(true, List.of("legacy-chunks"), null);
        InMemoryRegistry registry = new InMemoryRegistry();
        ElasticsearchIndexBootstrap bootstrap =
                new ElasticsearchIndexBootstrap(indexes, providerOf(registry.proxy),
                        new ChunkMappingBuilder(), properties(), 1);

        bootstrap.run(null);

        assertThat(indexes.calls).doesNotContain(
                "create:legacy-chunks", "alias:legacy-chunks");
        SearchIndexVersion unmatched = registry.rows.get(1);
        assertThat(unmatched.isSelected()).isTrue();
        assertThat(unmatched.getNeedsAttentionReason()).contains("not a managed kwiki-chunks");
        assertThat(unmatched.isPipelineSupported()).isFalse();
        assertThat(unmatched.isWriteEnabled()).isFalse();
        assertThat(bootstrap.isReady()).isTrue();
    }

    @Test
    void conflictingMappingIsAdoptedAsNeedsAttention() throws Exception {
        FakeIndexManager indexes = new FakeIndexManager(true, List.of("kwiki-chunks-v1"),
                "vector dimension mismatch: expected 1024");
        InMemoryRegistry registry = new InMemoryRegistry();
        ElasticsearchIndexBootstrap bootstrap =
                new ElasticsearchIndexBootstrap(indexes, providerOf(registry.proxy),
                        new ChunkMappingBuilder(), properties(), 1);

        bootstrap.run(null);

        SearchIndexVersion unmatched = registry.rows.get(1);
        assertThat(unmatched.getNeedsAttentionReason())
                .contains("does not match the deployed configuration");
        assertThat(unmatched.isSelected()).isTrue();
    }

    @Test
    void restartWithMetadataNeverCreatesOrSwitchesVersions() throws Exception {
        InMemoryRegistry registry = new InMemoryRegistry();
        registry.rows.put(2, SearchIndexVersion.bootstrapped(2, "kwiki-chunks-v2",
                deployedConfig(), "hash"));
        FakeIndexManager indexes = new FakeIndexManager(true, List.of("kwiki-chunks-v2"), null);
        ElasticsearchIndexBootstrap bootstrap =
                new ElasticsearchIndexBootstrap(indexes, providerOf(registry.proxy),
                        new ChunkMappingBuilder(), properties(), 1);

        bootstrap.run(null);

        // bootstrapVersion=1 不会创建/切换 v1；已有元数据时不做任何 ES 变更。
        assertThat(indexes.calls).containsExactly("available", "aliasExists");
        assertThat(registry.rows).doesNotContainKey(1);
        assertThat(registry.saves).isZero();
        assertThat(bootstrap.isReady()).isTrue();
    }

    @Test
    void crashWindowRepairsMissingAliasToSelectedVersion() throws Exception {
        InMemoryRegistry registry = new InMemoryRegistry();
        registry.rows.put(1, SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1",
                deployedConfig(), "hash"));
        FakeIndexManager indexes = new FakeIndexManager(false, List.of(), null);
        ElasticsearchIndexBootstrap bootstrap =
                new ElasticsearchIndexBootstrap(indexes, providerOf(registry.proxy),
                        new ChunkMappingBuilder(), properties(), 1);

        bootstrap.run(null);

        assertThat(indexes.calls).containsExactly("available", "aliasExists", "alias:kwiki-chunks-v1");
    }

    @Test
    void failsFastWhenElasticsearchClientIsUnavailable() {
        FakeIndexManager unavailable = new FakeIndexManager(false, List.of(), null) {
            @Override
            public boolean available() {
                calls.add("available");
                return false;
            }
        };
        ElasticsearchIndexBootstrap bootstrap = new ElasticsearchIndexBootstrap(
                unavailable, providerOf(new InMemoryRegistry().proxy), new ChunkMappingBuilder(),
                properties(), 1);

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires an Elasticsearch client");
        assertThat(bootstrap.isReady()).isFalse();
    }

    @Test
    void failsFastWithoutTheVersionRegistry() {
        FakeIndexManager indexes = new FakeIndexManager(false, List.of(), null);
        ElasticsearchIndexBootstrap bootstrap = new ElasticsearchIndexBootstrap(
                indexes, nullProvider(), new ChunkMappingBuilder(), properties(), 1);

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version registry");
    }

    @Test
    void incompatibleMappingAtFirstDeploymentFailsBeforeAlias() {
        FakeIndexManager indexes = new FakeIndexManager(false, List.of(),
                "vector dimension mismatch: expected 1024");
        ElasticsearchIndexBootstrap bootstrap = new ElasticsearchIndexBootstrap(
                indexes, providerOf(new InMemoryRegistry().proxy), new ChunkMappingBuilder(),
                properties(), 1);

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vector dimension mismatch");
        assertThat(indexes.calls).doesNotContain("alias:kwiki-chunks-v1");
    }

    @Test
    void rejectsNonPositiveVersion() {
        assertThatThrownBy(() -> new ElasticsearchIndexBootstrap(
                new FakeIndexManager(false, List.of(), null),
                providerOf(new InMemoryRegistry().proxy),
                new ChunkMappingBuilder(), properties(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }

    private static <T> ObjectProvider<T> nullProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return null;
            }
        };
    }

    /** 永不连接的假索引管理器：aliasExists 与 aliasTargets 由用例给定。 */
    private static class FakeIndexManager extends ElasticsearchIndexManager {
        final List<String> calls = new ArrayList<>();
        final boolean aliasExists;
        final List<String> aliasTargets;
        final String incompatibility;

        FakeIndexManager(boolean aliasExists, List<String> aliasTargets, String incompatibility) {
            super(nullProvider(), new ChunkMappingBuilder());
            this.aliasExists = aliasExists;
            this.aliasTargets = aliasTargets;
            this.incompatibility = incompatibility;
        }

        @Override
        public boolean available() {
            calls.add("available");
            return true;
        }

        @Override
        public boolean aliasExists() {
            calls.add("aliasExists");
            return aliasExists;
        }

        @Override
        public List<String> aliasTargets() {
            calls.add("targets");
            return aliasTargets;
        }

        @Override
        public String createVersionedIndex(String indexName, int embeddingDimensions) {
            calls.add("create:" + indexName);
            return indexName;
        }

        @Override
        public String validateIndex(String indexName, int expectedDimensions,
                                    int mappingSchemaVersion) {
            calls.add("validate:" + indexName + ":" + expectedDimensions);
            return incompatibility;
        }

        @Override
        public void createInitialAlias(String indexName) {
            calls.add("alias:" + indexName);
        }
    }
}
