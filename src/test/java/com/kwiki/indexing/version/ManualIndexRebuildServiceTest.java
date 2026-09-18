package com.kwiki.indexing.version;

import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManualIndexRebuildServiceTest {

    @Test
    void unchangedOfflineVersionIsRecreatedAndScannedInsideSharedCoordinatorWork() throws Exception {
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        VersionRebuildCoordinator coordinator = mock(VersionRebuildCoordinator.class);
        ElasticsearchIndexManager indexes = mock(ElasticsearchIndexManager.class);
        FixedRangeRebuildScanner scanner = mock(FixedRangeRebuildScanner.class);
        SearchIndexVersion version = builtOfflineVersion();
        SearchIndexRebuildRun run = SearchIndexRebuildRun.create(2, 2,
                RebuildRunKind.MANUAL, 1, "{}", "admin", "owner",
                Duration.ofMinutes(1), 12, Instant.EPOCH);
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(version));
        when(coordinator.startManual(any(), any())).thenAnswer(invocation -> {
            VersionRebuildCoordinator.RebuildWork work = invocation.getArgument(1);
            work.execute(run);
            return new VersionRebuildCoordinator.StartResult(true, 91L, false, "ACCEPTED");
        });
        ManualIndexRebuildService service = new ManualIndexRebuildService(
                versions, coordinator, indexes, scanner, properties(true));

        var result = service.rebuild(2, "admin");

        assertThat(result.accepted()).isTrue();
        verify(indexes).recreateOfflineVersion("kwiki-chunks-v2", 1024);
        verify(scanner).scan(run);
    }

    @Test
    void selectedVersionIsRejectedBeforeCoordinatorOrElasticsearchMutation() {
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        VersionRebuildCoordinator coordinator = mock(VersionRebuildCoordinator.class);
        ElasticsearchIndexManager indexes = mock(ElasticsearchIndexManager.class);
        when(versions.findByVersionNumber(1)).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config(), "mapping")));
        ManualIndexRebuildService service = new ManualIndexRebuildService(
                versions, coordinator, indexes, mock(FixedRangeRebuildScanner.class),
                properties(true));

        assertThatThrownBy(() -> service.rebuild(1, "admin"))
                .hasMessageContaining("switch away");
        verifyNoInteractions(coordinator, indexes);
    }

    private static SearchIndexVersion builtOfflineVersion() {
        SearchIndexVersion version = SearchIndexVersion.bootstrapped(
                2, "kwiki-chunks-v2", config(), "mapping");
        version.applySnapshot(IndexVersionStatusPolicy.disableWrites(
                IndexVersionStatusPolicy.unpublish(version.toSnapshot(false, false))));
        return version;
    }

    private static EditableIndexConfig config() {
        return new EditableIndexConfig("parser", "chunker", "default", "model", 1024, 1);
    }

    private static IndexingProperties properties(boolean enabled) {
        return new IndexingProperties(null, null,
                new IndexingProperties.Rebuild(50, 2, 20),
                new IndexingProperties.Catchup(100, Duration.ofSeconds(30)),
                new IndexingProperties.Capacity(20, 8, Duration.ofSeconds(5)),
                new IndexingProperties.Management(enabled));
    }
}
