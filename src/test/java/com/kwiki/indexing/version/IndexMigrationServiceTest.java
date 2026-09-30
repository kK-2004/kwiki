package com.kwiki.indexing.version;

import com.kwiki.indexing.search.ChunkMappingBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IndexMigrationServiceTest {

    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final VersionRebuildCoordinator coordinator = mock(VersionRebuildCoordinator.class);
    private final FixedRangeRebuildScanner scanner = mock(FixedRangeRebuildScanner.class);
    private final EditableIndexConfig config =
            new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "model", 1024, 3);
    private IndexMigrationService service;
    private SearchIndexVersion version;

    @BeforeEach
    void setUp() {
        service = new IndexMigrationService(versions, coordinator, scanner,
                IndexVersionWriteServiceTest.properties(true), null);
        version = new SearchIndexVersion(2, "kwiki-chunks-v2", config,
                new ChunkMappingBuilder().mappingHash(1024, 3));
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(version));
    }

    @Test
    void 写入关闭时拒绝迁移_不发起run() {
        assertThatThrownBy(() -> service.migrate(2, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enable writes");
        verifyNoInteractions(coordinator);
    }

    @Test
    void 写入开启时发起MIGRATION_run() {
        version.startWriteSession(42L);
        when(coordinator.startMigration(any(), any())).thenReturn(
                new VersionRebuildCoordinator.StartResult(true, 9L, false, "ACCEPTED"));

        VersionRebuildCoordinator.StartResult result = service.migrate(2, "admin");

        ArgumentCaptor<VersionRebuildCoordinator.Request> request =
                ArgumentCaptor.forClass(VersionRebuildCoordinator.Request.class);
        verify(coordinator).startMigration(request.capture(), any());
        assertThat(request.getValue().kind()).isEqualTo(RebuildRunKind.MIGRATION);
        assertThat(request.getValue().versionNumber()).isEqualTo(2);
        assertThat(result.runId()).isEqualTo(9L);
    }

    @Test
    void 已迁移版本拒绝再次迁移() {
        SearchIndexVersion published = SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config,
                new ChunkMappingBuilder().mappingHash(1024, 3));
        when(versions.findByVersionNumber(1)).thenReturn(Optional.of(published));
        assertThatThrownBy(() -> service.migrate(1, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already migrated");
    }
}
