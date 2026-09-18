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
        v2.disableByAdministrator();
        SearchIndexVersion v3 = builtVersion(3);
        v3.disableByAdministrator();
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

    private static SearchIndexVersion builtVersion(int versionNumber) {
        SearchIndexVersion version = new SearchIndexVersion(
                versionNumber, "kwiki-chunks-v" + versionNumber, CONFIG, "mapping");
        version.applySnapshot(IndexVersionStatusPolicy.completeBuild(
                version.toSnapshot(false, false), 1));
        return version;
    }
}
