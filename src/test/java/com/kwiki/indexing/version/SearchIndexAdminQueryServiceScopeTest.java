package com.kwiki.indexing.version;

import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.indexing.gray.IndexVersionKbScope;
import com.kwiki.indexing.job.IndexingJobTargetStore;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchIndexAdminQueryServiceScopeTest {
    private static final EditableIndexConfig CONFIG = new EditableIndexConfig(
            "parser-1", "chunker-1", "default", "embedding-1", 1024, 1);

    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final IndexVersionRetentionAdvisor retention = mock(IndexVersionRetentionAdvisor.class);
    private final IndexVersionKbScope scope = mock(IndexVersionKbScope.class);
    private final SearchIndexAdminQueryService service = new SearchIndexAdminQueryService(versions,
            mock(SearchIndexRebuildRunRepository.class), mock(SearchIndexRebuildRangeRepository.class),
            mock(SearchIndexValidationReportRepository.class), mock(SearchIndexAuditRepository.class),
            retention, mock(ElasticsearchIndexManager.class), mock(IndexingJobTargetStore.class),
            new SimpleMeterRegistry(), mock(MultimodalSwitchReadiness.class));

    private SearchIndexAdminQueryService.VersionView only(int number, boolean scoped) {
        when(versions.findByDeletedAtIsNullOrderByVersionNumberAsc())
                .thenReturn(List.of(new SearchIndexVersion(number, "kwiki-chunks-v" + number, CONFIG, "mapping")));
        when(retention.recommend()).thenReturn(new IndexVersionRetentionAdvisor.Recommendation(2, List.of(
                new IndexVersionRetentionAdvisor.VersionRecommendation(number, "kwiki-chunks-v" + number,
                        false, true))));
        when(scope.isScoped(number)).thenReturn(scoped);
        service.setKbScope(scope);
        return service.versions().get(0);
    }

    @Test
    void 灰度版本在全局索引页只保留删除操作() {
        var view = only(4, true);
        assertThat(view.kbScoped()).isTrue();
        assertThat(view.allowedActions())
                .containsEntry("edit", false).containsEntry("writeToggle", false)
                .containsEntry("migrate", false).containsEntry("validate", false)
                .containsEntry("select", false)
                .containsEntry("delete", true);
    }

    @Test
    void 全局版本保持原有生命周期操作() {
        var view = only(3, false);
        assertThat(view.kbScoped()).isFalse();
        assertThat(view.allowedActions()).containsEntry("edit", true)
                .containsEntry("writeToggle", true).containsEntry("delete", true);
    }
}
