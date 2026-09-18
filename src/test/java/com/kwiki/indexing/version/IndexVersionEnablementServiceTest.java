package com.kwiki.indexing.version;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexVersionEnablementServiceTest {

    private static final EditableIndexConfig CONFIG = new EditableIndexConfig(
            "parser-1", "chunker-1", "default", "embedding-1", 1024, 1);

    @Test
    void disableRemovesOnlyFutureWriteEnrollmentAndMarksBehind() {
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
        SwitchPreparationService preparation = mock(SwitchPreparationService.class);
        SearchIndexVersion version = builtVersion(2);
        version.enableForSwitchPreparation();
        when(versions.findByVersionNumberForUpdate(2)).thenReturn(Optional.of(version));

        new IndexVersionEnablementService(versions, runs, preparation).disable(2);

        assertThat(version.isAdminDisabled()).isTrue();
        assertThat(version.isWriteEnabled()).isFalse();
        assertThat(version.getCatchupStatus()).isEqualTo(IndexCatchupStatus.BEHIND.name());
    }

    @Test
    void selectedOrBusyVersionCannotBeDisabled() {
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
        SearchIndexVersion selected = SearchIndexVersion.bootstrapped(
                1, "kwiki-chunks-v1", CONFIG, "mapping");
        when(versions.findByVersionNumberForUpdate(1)).thenReturn(Optional.of(selected));
        IndexVersionEnablementService service = new IndexVersionEnablementService(
                versions, runs, mock(SwitchPreparationService.class));

        assertThatThrownBy(() -> service.disable(1))
                .hasMessageContaining("selected version");

        SearchIndexVersion busy = builtVersion(2);
        when(versions.findByVersionNumberForUpdate(2)).thenReturn(Optional.of(busy));
        when(runs.existsByVersionNumberAndStateIn(2, List.of("RUNNING", "PAUSED")))
                .thenReturn(true);
        assertThatThrownBy(() -> service.disable(2))
                .hasMessageContaining("active rebuild or catch-up");
    }

    @Test
    void reenableEnrollsFutureWritesAndMustStartCatchupPreparation() {
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
        SwitchPreparationService preparation = mock(SwitchPreparationService.class);
        SearchIndexVersion version = builtVersion(3);
        version.disableByAdministrator();
        when(versions.findByVersionNumberForUpdate(3)).thenReturn(Optional.of(version));
        when(preparation.prepare(3)).thenReturn(
                new SwitchPreparationService.Preparation(7, 9, List.of()));

        new IndexVersionEnablementService(versions, runs, preparation).reenable(3);

        assertThat(version.isAdminDisabled()).isFalse();
        assertThat(version.isWriteEnabled()).isTrue();
        assertThat(version.getCatchupStatus()).isEqualTo(IndexCatchupStatus.BEHIND.name());
        verify(versions).flush();
        verify(preparation).prepare(3);
    }

    private static SearchIndexVersion builtVersion(int versionNumber) {
        SearchIndexVersion version = new SearchIndexVersion(
                versionNumber, "kwiki-chunks-v" + versionNumber, CONFIG, "mapping");
        version.applySnapshot(IndexVersionStatusPolicy.completeBuild(
                version.toSnapshot(false, false), 1));
        return version;
    }
}
