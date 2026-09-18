package com.kwiki.indexing.version;

import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchIndexDeletionServiceTest {
    private static final EditableIndexConfig CONFIG = new EditableIndexConfig(
            "parser-1", "chunker-1", "default", "embedding-1", 1024, 1);

    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
    private final SearchIndexIdempotencyRepository keys = mock(SearchIndexIdempotencyRepository.class);
    private final SearchIndexAuditRepository audits = mock(SearchIndexAuditRepository.class);
    private final ElasticsearchIndexManager indexes = mock(ElasticsearchIndexManager.class);
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final SearchIndexDeletionService service = new SearchIndexDeletionService(
            versions, runs, keys, audits, indexes, jdbc);

    @BeforeEach
    void setUp() {
        when(keys.findById(anyString())).thenReturn(Optional.empty());
        when(keys.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(audits.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(indexes.indexNameFor(any(Integer.class))).thenAnswer(
                invocation -> "kwiki-chunks-v" + invocation.getArgument(0));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Integer.class)))
                .thenReturn(0L);
    }

    @Test
    void selectedWriteEnabledBusyAndMalformedVersionsCannotBeDeleted() throws Exception {
        SearchIndexVersion selected = SearchIndexVersion.bootstrapped(
                1, "kwiki-chunks-v1", CONFIG, "mapping");
        reject(selected, "kwiki-chunks-v1", "selected version");

        SearchIndexVersion writable = builtVersion(2, "kwiki-chunks-v2");
        writable.enableForSwitchPreparation();
        reject(writable, "kwiki-chunks-v2", "write-enabled version");

        SearchIndexVersion busy = disabledVersion(3, "kwiki-chunks-v3");
        when(runs.existsByVersionNumberAndStateIn(3, List.of("RUNNING", "PAUSED")))
                .thenReturn(true);
        reject(busy, "kwiki-chunks-v3", "active rebuild or catch-up");

        SearchIndexVersion malformed = disabledVersion(4, "other-index");
        reject(malformed, "other-index", "not exact");
        verify(indexes, never()).deletePhysicalIndex(anyString());
    }

    @Test
    void exactConfirmationAndNoActiveJobsAreRequired() {
        SearchIndexVersion version = disabledVersion(5, "kwiki-chunks-v5");
        when(versions.findByVersionNumberForUpdate(5)).thenReturn(Optional.of(version));
        assertThatThrownBy(() -> service.delete(5, "kwiki-chunks-v4", "key-1", "admin"))
                .hasMessageContaining("exact physical index name");

        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(5))).thenReturn(1L);
        assertThatThrownBy(() -> service.delete(5, "kwiki-chunks-v5", "key-2", "admin"))
                .hasMessageContaining("active indexing jobs");
    }

    @Test
    void eligibleDeletionIsAcknowledgedTombstonedAndNeverMutatesAlias() throws Exception {
        SearchIndexVersion version = disabledVersion(6, "kwiki-chunks-v6");
        when(versions.findByVersionNumberForUpdate(6)).thenReturn(Optional.of(version));
        when(indexes.deletePhysicalIndex("kwiki-chunks-v6")).thenReturn(true);

        SearchIndexDeletionService.DeletionResult result = service.delete(
                6, "kwiki-chunks-v6", "delete-6", "admin");

        assertThat(result.outcome()).isEqualTo("SUCCESS:ACKNOWLEDGED");
        assertThat(result.replayed()).isFalse();
        assertThat(version.getDeletedAt()).isNotNull();
        assertThat(version.isWriteEnabled()).isFalse();
        verify(indexes).deletePhysicalIndex("kwiki-chunks-v6");
        verify(indexes, never()).atomicSwitchAlias(anyString(), anyString());
        verify(indexes, never()).activateAlias(anyString());
    }

    @Test
    void repeatedIdempotencyKeyReplaysOutcomeWithoutDeletingAgain() throws Exception {
        SearchIndexIdempotency prior = SearchIndexIdempotency.pending(
                "delete-7", "DELETE_PHYSICAL_INDEX", 7, "admin");
        prior.complete("SUCCESS:ACKNOWLEDGED");
        when(keys.findById("delete-7")).thenReturn(Optional.of(prior));

        var result = service.delete(7, "kwiki-chunks-v7", "delete-7", "admin");

        assertThat(result.replayed()).isTrue();
        assertThat(result.outcome()).isEqualTo("SUCCESS:ACKNOWLEDGED");
        verify(indexes, never()).deletePhysicalIndex(anyString());
    }

    private void reject(SearchIndexVersion version, String confirmation, String message) {
        when(versions.findByVersionNumberForUpdate(version.getVersionNumber()))
                .thenReturn(Optional.of(version));
        assertThatThrownBy(() -> service.delete(version.getVersionNumber(), confirmation,
                "key-" + version.getVersionNumber(), "admin"))
                .hasMessageContaining(message);
    }

    private static SearchIndexVersion disabledVersion(int number, String physicalName) {
        SearchIndexVersion version = builtVersion(number, physicalName);
        version.disableByAdministrator();
        return version;
    }

    private static SearchIndexVersion builtVersion(int number, String physicalName) {
        SearchIndexVersion version = new SearchIndexVersion(number, physicalName, CONFIG, "mapping");
        version.applySnapshot(IndexVersionStatusPolicy.completeBuild(
                version.toSnapshot(false, false), 1));
        return version;
    }
}
