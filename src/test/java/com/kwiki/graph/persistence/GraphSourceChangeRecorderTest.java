package com.kwiki.graph.persistence;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphSourceChangeRecorderTest {

    private final GraphSourceEpochService epochs = mock(GraphSourceEpochService.class);
    private final GraphRebuildRequestRepository requests = mock(GraphRebuildRequestRepository.class);
    private final GraphSourceChangeRecorder recorder = new GraphSourceChangeRecorder(epochs, requests);

    @Test
    void contentChangedAdvancesContentEpochAndUpsertsWithCurrentPair() {
        when(epochs.current(7L)).thenReturn(new long[]{4, 2});

        recorder.contentChanged(7L);

        verify(epochs).advanceContentEpoch(7L);
        verify(epochs, never()).advanceSecurityEpoch(anyLong());
        verify(requests).markDirty(7L, 4, 2, false);
    }

    @Test
    void securityChangedAdvancesSecurityEpochOnly() {
        when(epochs.current(7L)).thenReturn(new long[]{3, 9});

        recorder.securityChanged(7L);

        verify(epochs).advanceSecurityEpoch(7L);
        verify(epochs, never()).advanceContentEpoch(anyLong());
        verify(requests).markDirty(7L, 3, 9, false);
    }

    @Test
    void hardDeletedMarksHardDeleteOnTheRebuildRequest() {
        when(epochs.current(7L)).thenReturn(new long[]{5, 2});

        recorder.hardDeleted(7L);

        verify(epochs).advanceContentEpoch(7L);
        verify(requests).markDirty(eq(7L), eq(5L), eq(2L), anyBoolean());
        verify(requests).markDirty(7L, 5, 2, true);
    }
}
