package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphSnapshotAutoPublishServiceTest {

    private static final GraphSnapshot SNAPSHOT = new GraphSnapshot(91, 7, 42, 4, 7,
            "kwiki-chunks-v4", "kwiki-communities-v7-kb7", "entity-linking-v1",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 5, 2);
    private static final GraphValidationChecklist CHECKLIST = new GraphValidationChecklist(
            true, true, true, true, true, true, true);

    @Test
    void defaultManualPublishKeepsCandidateWaitingForAdministrator() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSnapshotPublicationService publication =
                mock(GraphSnapshotPublicationService.class);
        when(repository.findBatchAutoPublishForRun(11L)).thenReturn(false);
        GraphSnapshotAutoPublishService service =
                new GraphSnapshotAutoPublishService(repository, publication);

        assertThat(service.publishIfAutoPublishEnabled(11L, SNAPSHOT,
                GraphSnapshotState.READY, CHECKLIST, null)).isFalse();

        verify(publication, never()).publish(any(), any(), any(), any());
    }

    @Test
    void autoPublishReusesTheSamePublicationGate() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSnapshotPublicationService publication =
                mock(GraphSnapshotPublicationService.class);
        when(repository.findBatchAutoPublishForRun(11L)).thenReturn(true);
        when(publication.publish(SNAPSHOT, GraphSnapshotState.READY, CHECKLIST, null))
                .thenReturn(true);
        GraphSnapshotAutoPublishService service =
                new GraphSnapshotAutoPublishService(repository, publication);

        assertThat(service.publishIfAutoPublishEnabled(11L, SNAPSHOT,
                GraphSnapshotState.READY, CHECKLIST, null)).isTrue();

        verify(publication).publish(SNAPSHOT, GraphSnapshotState.READY, CHECKLIST, null);
    }

    @Test
    void manualPublishIsAlwaysAvailableRegardlessOfBatchFlag() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSnapshotPublicationService publication =
                mock(GraphSnapshotPublicationService.class);
        when(publication.publish(SNAPSHOT, GraphSnapshotState.READY, CHECKLIST, 88L))
                .thenReturn(true);
        GraphSnapshotAutoPublishService service =
                new GraphSnapshotAutoPublishService(repository, publication);

        assertThat(service.publishManually(SNAPSHOT, GraphSnapshotState.READY,
                CHECKLIST, 88L)).isTrue();
        verify(repository, never()).findBatchAutoPublishForRun(anyLong());
    }

    @Test
    void publishingCandidateChunkVersionOnlyTouchesItsOwnPairingAndNeverChunkAlias() {
        JdbcTemplateShim shim = new JdbcTemplateShim();
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        when(repository.findBatchAutoPublishForRun(11L)).thenReturn(true);
        when(repository.findPublication(7, 4)).thenReturn(java.util.Optional.empty());
        when(repository.compareAndSetPublication(anyLong(), anyInt(), any(), anyLong(),
                anyLong(), anyLong(), any(Instant.class))).thenAnswer(invocation -> {
            shim.casCalls++;
            shim.lastKbId = invocation.getArgument(0);
            shim.lastChunkIndexVersion = invocation.getArgument(1);
            return true;
        });
        GraphSourceEpochService epochs = mock(GraphSourceEpochService.class);
        when(epochs.currentForUpdate(7)).thenReturn(new long[]{5, 2});
        GraphSnapshotPublicationService publication =
                new GraphSnapshotPublicationService(repository, epochs);
        GraphSnapshotAutoPublishService service =
                new GraphSnapshotAutoPublishService(repository, publication);

        assertThat(service.publishIfAutoPublishEnabled(11L, SNAPSHOT,
                GraphSnapshotState.READY, CHECKLIST, null)).isTrue();

        assertThat(shim.casCalls).isEqualTo(1);
        assertThat(shim.lastKbId).isEqualTo(7L);
        assertThat(shim.lastChunkIndexVersion).isEqualTo(4);
    }

    /** 只记录发布事务对权威指针的调用，证明不涉及别名或其他版本配对。 */
    private static final class JdbcTemplateShim {
        int casCalls;
        long lastKbId;
        int lastChunkIndexVersion;
    }
}
