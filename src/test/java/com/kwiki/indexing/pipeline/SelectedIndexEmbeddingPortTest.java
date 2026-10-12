package com.kwiki.indexing.pipeline;

import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import com.kwiki.testutil.StubModelRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelectedIndexEmbeddingPortTest {

    private final ChunkEmbeddingPort defaultPort = texts -> List.of(new float[]{1f});
    private final ChunkEmbeddingPort altPort = texts -> List.of(new float[]{2f});
    private final StubModelRegistry models = StubModelRegistry.defaultEmbedding("text-embedding-v4", 1024, defaultPort)
            .withEmbedding("bailian", "text-embedding-v5", 2048, altPort);

    private static SearchIndexVersion selected(String provider, String model, int dims) {
        SearchIndexVersion version = mock(SearchIndexVersion.class);
        when(version.editableConfig()).thenReturn(new EditableIndexConfig("p", "c", provider, model, dims, "s"));
        return version;
    }

    @Test
    void 使用已发布版本引用的模型() {
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        SearchIndexVersion v = selected("bailian", "text-embedding-v5", 2048);
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(v));
        var port = new SelectedIndexEmbeddingPort(models, versions);

        assertThat(port.embed(List.of("q")).get(0)[0]).isEqualTo(2f);
        assertThat(port.cacheIdentity()).isEqualTo("bailian:text-embedding-v5:2048");
    }

    @Test
    void 没有已发布版本时退回default模型() {
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        when(versions.findBySelectedTrue()).thenReturn(Optional.empty());
        var port = new SelectedIndexEmbeddingPort(models, versions);

        assertThat(port.embed(List.of("q")).get(0)[0]).isEqualTo(1f);
    }

    @Test
    void 维度不一致或模型缺失时明确失败() {
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        SearchIndexVersion v = selected("bailian", "text-embedding-v5", 1024);
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(v));
        var port = new SelectedIndexEmbeddingPort(models, versions);

        assertThat(port.cacheIdentity()).isEqualTo("unavailable");
        assertThatThrownBy(() -> port.embed(List.of("q"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 已发布版本缓存十秒() {
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        SearchIndexVersion v = selected("default", "text-embedding-v4", 1024);
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(v));
        AtomicLong now = new AtomicLong();
        var port = new SelectedIndexEmbeddingPort(models, versions, now::get);

        port.cacheIdentity();
        port.cacheIdentity();
        verify(versions, times(1)).findBySelectedTrue();
        now.addAndGet(11_000_000_000L);
        port.cacheIdentity();
        verify(versions, times(2)).findBySelectedTrue();
    }
}
