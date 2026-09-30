package com.kwiki.indexing.version;

import com.kwiki.indexing.search.ChunkMappingBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SearchIndexSelectionRegistryTest {

    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final SearchIndexSelectionRegistry registry = new SearchIndexSelectionRegistry(versions);
    private final EditableIndexConfig config =
            new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "model", 1024, 3);
    private final String hash = new ChunkMappingBuilder().mappingHash(1024, 3);

    @Test
    void 选择版本不再开启其他版本写入() {
        SearchIndexVersion v1 = SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config, hash);
        SearchIndexVersion v2 = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        v2.startWriteSession(42L);
        SearchIndexVersion v3 = new SearchIndexVersion(3, "kwiki-chunks-v3", config, hash);
        when(versions.findAllActiveForUpdate()).thenReturn(List.of(v1, v2, v3));

        registry.select(2);

        assertThat(v2.isSelected()).isTrue();
        assertThat(v1.isSelected()).isFalse();
        assertThat(v3.isWriteEnabled()).isFalse();
    }

    @Test
    void 写入关闭的目标拒绝选择() {
        SearchIndexVersion v1 = SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config, hash);
        SearchIndexVersion v2 = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        when(versions.findAllActiveForUpdate()).thenReturn(List.of(v1, v2));
        assertThatThrownBy(() -> registry.select(2)).isInstanceOf(IllegalStateException.class);
    }
}
