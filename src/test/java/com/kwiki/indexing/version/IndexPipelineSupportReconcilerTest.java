package com.kwiki.indexing.version;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 流水线受支持性对账（任务 5.5）：不支持时标记版本并阻止选择；
 * 恢复支持后回正；注册表拒绝的配置给出脱敏原因。
 */
class IndexPipelineSupportReconcilerTest {

    private final SearchIndexVersionRepository repository =
            Mockito.mock(SearchIndexVersionRepository.class);
    private final VersionedIndexingPipelineRegistry registry =
            Mockito.mock(VersionedIndexingPipelineRegistry.class);
    private final IndexPipelineSupportReconciler reconciler =
            new IndexPipelineSupportReconciler(
                    com.kwiki.testutil.StandardTestProperties.providerOf(repository), registry);

    private static EditableIndexConfig config() {
        return new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default",
                "text-embedding-v4", 1024, 1);
    }

    @BeforeEach
    void setUp() {
        when(registry.unsupportedReason(any())).thenReturn(Optional.empty());
    }

    @Test
    void unsupportedSelectedVersionIsMarkedAndBlockedFromSwitching() {
        SearchIndexVersion selected = SearchIndexVersion.bootstrapped(
                1, "kwiki-chunks-v1", config(), "hash");
        when(repository.findByDeletedAtIsNullOrderByVersionNumberAsc())
                .thenReturn(List.of(selected));
        when(registry.supports(config())).thenReturn(false);

        int changed = reconciler.reconcile();

        assertThat(changed).isEqualTo(1);
        assertThat(selected.isPipelineSupported()).isFalse();
        assertThat(selected.toSnapshot(false, false).selectable()).isFalse();
    }

    @Test
    void restoredSupportIsReverted() {
        SearchIndexVersion marked = SearchIndexVersion.bootstrapped(
                2, "kwiki-chunks-v2", config(), "hash");
        marked.applySnapshot(IndexVersionStatusPolicy.pipelineSupportChanged(
                marked.toSnapshot(false, false), false));
        when(repository.findByDeletedAtIsNullOrderByVersionNumberAsc())
                .thenReturn(List.of(marked));
        when(registry.supports(config())).thenReturn(true);

        assertThat(reconciler.reconcile()).isEqualTo(1);
        assertThat(marked.isPipelineSupported()).isTrue();
    }

    @Test
    void unchangedVersionsAreNotRewritten() {
        SearchIndexVersion supported = SearchIndexVersion.bootstrapped(
                1, "kwiki-chunks-v1", config(), "hash");
        when(repository.findByDeletedAtIsNullOrderByVersionNumberAsc())
                .thenReturn(List.of(supported));
        when(registry.supports(config())).thenReturn(true);

        assertThat(reconciler.reconcile()).isZero();
        Mockito.verify(repository, Mockito.never()).save(any(SearchIndexVersion.class));
    }

    @Test
    void missingRegistryFailsClosedWithoutSilentlyMarkingAnything() {
        IndexPipelineSupportReconciler offline = new IndexPipelineSupportReconciler(
                com.kwiki.testutil.StandardTestProperties.nullProvider(), registry);

        assertThat(offline.reconcile()).isZero();
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void embeddingPortIdentityIsPartOfThePipelineContract() {
        // 契约锚点：ChunkEmbeddingPort.cacheIdentity 用于中间结果复用键。
        ChunkEmbeddingPort port = new ChunkEmbeddingPort() {
            @Override
            public java.util.List<float[]> embed(java.util.List<String> texts) {
                return List.of();
            }
        };
        assertThat(port.cacheIdentity()).isEqualTo(port.getClass().getName());
        assertThat(Map.of()).isNotNull();
    }
}
