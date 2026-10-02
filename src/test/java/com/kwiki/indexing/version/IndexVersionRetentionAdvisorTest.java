package com.kwiki.indexing.version;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexVersionRetentionAdvisorTest {

    private static final EditableIndexConfig CONFIG = new EditableIndexConfig(
            "parser-1", "chunker-1", "default", "embedding-1", 1024, 1);

    @Test
    void retainsSelectedAndNewestWhileOnlyOlderDisabledVersionIsCandidate() {
        SearchIndexVersionRepository repository = mock(SearchIndexVersionRepository.class);
        SearchIndexVersion selected = SearchIndexVersion.bootstrapped(
                1, "kwiki-chunks-v1", CONFIG, "mapping");
        SearchIndexVersion v2 = builtVersion(2);
        v2.endWriteSession();
        SearchIndexVersion v3 = builtVersion(3);
        v3.endWriteSession();
        when(repository.findByDeletedAtIsNullOrderByVersionNumberAsc())
                .thenReturn(List.of(selected, v2, v3));

        IndexVersionRetentionAdvisor.Recommendation result =
                new IndexVersionRetentionAdvisor(repository).recommend();

        assertThat(result.defaultRetainedCount()).isEqualTo(2);
        assertThat(result.versions()).extracting(
                        IndexVersionRetentionAdvisor.VersionRecommendation::versionNumber,
                        IndexVersionRetentionAdvisor.VersionRecommendation::retained,
                        IndexVersionRetentionAdvisor.VersionRecommendation::cleanupCandidate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, true, false),
                        org.assertj.core.groups.Tuple.tuple(2, false, true),
                        org.assertj.core.groups.Tuple.tuple(3, true, false));
    }

    @Test
    void 灰度版本不占保留名额_停用后成为可清理() {
        // v1 已选中、v2 全局版本、v3 灰度版本且已停用
        SearchIndexVersionRepository repository = mock(SearchIndexVersionRepository.class);
        SearchIndexVersion selected = SearchIndexVersion.bootstrapped(
                1, "kwiki-chunks-v1", CONFIG, "mapping");
        SearchIndexVersion v2 = builtVersion(2);
        SearchIndexVersion v3 = builtVersion(3);
        v3.endWriteSession();
        when(repository.findByDeletedAtIsNullOrderByVersionNumberAsc())
                .thenReturn(List.of(selected, v2, v3));
        com.kwiki.indexing.gray.IndexVersionKbScope scope =
                org.mockito.Mockito.mock(com.kwiki.indexing.gray.IndexVersionKbScope.class);
        org.mockito.Mockito.when(scope.isScoped(3)).thenReturn(true);
        IndexVersionRetentionAdvisor advisor = new IndexVersionRetentionAdvisor(repository);
        advisor.setKbScope(scope);
        var items = advisor.recommend().versions();
        assertThat(items).filteredOn(item -> item.versionNumber() == 2)
                .singleElement().extracting(IndexVersionRetentionAdvisor.VersionRecommendation::retained).isEqualTo(true);
        assertThat(items).filteredOn(item -> item.versionNumber() == 3)
                .singleElement().extracting(IndexVersionRetentionAdvisor.VersionRecommendation::cleanupCandidate).isEqualTo(true);
    }

    @Test
    void 从未开启写入的旧版本也可清理() {
        SearchIndexVersionRepository repository = mock(SearchIndexVersionRepository.class);
        SearchIndexVersion selected = SearchIndexVersion.bootstrapped(
                1, "kwiki-chunks-v1", CONFIG, "mapping");
        SearchIndexVersion neverEnabled = new SearchIndexVersion(2, "kwiki-chunks-v2", CONFIG, "mapping");
        SearchIndexVersion v3 = builtVersion(3);
        when(repository.findByDeletedAtIsNullOrderByVersionNumberAsc())
                .thenReturn(List.of(selected, neverEnabled, v3));

        var items = new IndexVersionRetentionAdvisor(repository).recommend().versions();

        assertThat(items).filteredOn(item -> item.versionNumber() == 2)
                .singleElement().extracting(IndexVersionRetentionAdvisor.VersionRecommendation::cleanupCandidate)
                .isEqualTo(true);
    }

    private static SearchIndexVersion builtVersion(int versionNumber) {
        SearchIndexVersion version = new SearchIndexVersion(
                versionNumber, "kwiki-chunks-v" + versionNumber, CONFIG, "mapping");
        version.applySnapshot(IndexVersionStatusPolicy.completeBuild(
                version.toSnapshot(false), 1));
        return version;
    }
}
