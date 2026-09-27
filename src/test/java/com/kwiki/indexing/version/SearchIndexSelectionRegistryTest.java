package com.kwiki.indexing.version;

import com.kwiki.indexing.gray.IndexVersionKbScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchIndexSelectionRegistryTest {

    private static final EditableIndexConfig CONFIG = new EditableIndexConfig(
            "parser-1", "chunker-1", "default", "embedding-1", 1024, 1);

    @Test
    void 全局选择不会开启灰度版本的写入_全局版本照常开启() {
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        SearchIndexVersion source = SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", CONFIG, "mapping");
        SearchIndexVersion target = built(2);
        SearchIndexVersion other = built(3);
        // 灰度 A 处于 CREATED：尚未构建，写入关闭
        SearchIndexVersion gray = new SearchIndexVersion(4, "kwiki-chunks-v4", CONFIG, "mapping");
        when(versions.findAllActiveForUpdate()).thenReturn(List.of(source, target, other, gray));
        IndexVersionKbScope scope = mock(IndexVersionKbScope.class);
        when(scope.isScoped(4)).thenReturn(true);
        SearchIndexSelectionRegistry registry = new SearchIndexSelectionRegistry(versions);
        registry.setKbScope(scope);

        registry.select(2);

        assertThat(target.isSelected()).isTrue();
        assertThat(source.isSelected()).isFalse();
        assertThat(other.isWriteEnabled()).isTrue();
        assertThat(gray.isWriteEnabled()).isFalse();
    }

    private static SearchIndexVersion built(int number) {
        SearchIndexVersion version = new SearchIndexVersion(number, "kwiki-chunks-v" + number, CONFIG, "mapping");
        version.applySnapshot(IndexVersionStatusPolicy.completeBuild(version.toSnapshot(false, false), 1));
        return version;
    }
}
