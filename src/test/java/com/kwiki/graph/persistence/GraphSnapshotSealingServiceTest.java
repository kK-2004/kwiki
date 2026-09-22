package com.kwiki.graph.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphSnapshotSealingServiceTest {

    @Test
    void hardFailurePersistsDiagnosisAndNeverSealsReady() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        when(repository.persistValidationReport(eq(91L), contains("SOURCE_COVERAGE")))
                .thenReturn(true);
        GraphSnapshotSealingService service = new GraphSnapshotSealingService(repository);
        GraphSnapshotValidationReport failed = new GraphSnapshotValidationReport(
                7, 42, 1, 0, 0, 0, 0, 0, 0,
                List.of(GraphSnapshotValidationReport.Check.fail(
                        GraphSnapshotValidationReport.SOURCE_COVERAGE, "notReady=1")));

        assertThat(service.seal(91L, failed)).isFalse();
        verify(repository).persistValidationReport(eq(91L), contains("SOURCE_COVERAGE"));
        verify(repository, never()).sealSnapshotReady(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void passingReportSealsReadyWithSingleConditionalUpdate() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        when(repository.sealSnapshotReady(eq(91L), contains("\"passed\"")))
                .thenReturn(true);
        GraphSnapshotSealingService service = new GraphSnapshotSealingService(repository);
        GraphSnapshotValidationReport passed = new GraphSnapshotValidationReport(
                7, 42, 1, 1, 0, 0, 0, 0, 0, GraphSnapshotValidationReport.REQUIRED_CHECKS.stream()
                        .map(name -> GraphSnapshotValidationReport.Check.pass(name, "ok"))
                        .toList());

        assertThat(service.seal(91L, passed)).isTrue();
        verify(repository).sealSnapshotReady(eq(91L), contains("\"passed\""));
    }

    @Test
    void reportMissingAnyRequiredCheckCannotSealReady() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSnapshotSealingService service = new GraphSnapshotSealingService(repository);
        GraphSnapshotValidationReport partial = new GraphSnapshotValidationReport(
                7, 42, 1, 1, 0, 0, 0, 0, 0, List.of(
                        GraphSnapshotValidationReport.Check.pass(
                                GraphSnapshotValidationReport.SOURCE_COVERAGE, "ok")));

        assertThat(service.seal(91L, partial)).isFalse();
        verify(repository).persistValidationReport(eq(91L), contains("SOURCE_COVERAGE"));
        verify(repository, never()).sealSnapshotReady(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void missingReportIsRejected() {
        GraphSnapshotSealingService service =
                new GraphSnapshotSealingService(mock(GraphBuildRepository.class));
        assertThatThrownBy(() -> service.seal(91L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
