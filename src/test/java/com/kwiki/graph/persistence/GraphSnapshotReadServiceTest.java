package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSnapshot;
import com.kwiki.graph.GraphSnapshotPin;
import com.kwiki.graph.config.GraphProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphSnapshotReadServiceTest {

    private static final GraphSnapshot SNAPSHOT = new GraphSnapshot(91, 7, 42, 12, 3,
            "kwiki-chunks-v12", "kwiki-communities-v3-kb7", "entity-linking-v1",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 5, 2);

    @Test
    void pinResolvesPairingOnceAcquiresLeaseAndUsesControlledPhysicalIndexes() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        when(repository.findActivePublicationSnapshot(7, 12))
                .thenReturn(Optional.of(new GraphSnapshotEntry(SNAPSHOT,
                        GraphSnapshotState.PUBLISHED)));
        when(repository.acquireReadLease(eq(91L), any(Instant.class))).thenReturn(555L);
        GraphSnapshotReadService service = service(repository);

        GraphSnapshotPin pin = service.pin(7, 12);

        assertThat(pin.snapshot()).isEqualTo(SNAPSHOT);
        assertThat(pin.leaseId()).isEqualTo(555L);
        assertThat(pin.snapshot().communityPhysicalIndex())
                .isEqualTo("kwiki-communities-v3-kb7");
    }

    @Test
    void missingPublishedPairingDisablesEnhancementInsteadOfBlockingRequest() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        when(repository.findActivePublicationSnapshot(anyLong(), anyInt()))
                .thenReturn(Optional.empty());
        GraphSnapshotReadService service = service(repository);

        assertThatThrownBy(() -> service.pin(7, 12))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).acquireReadLease(anyLong(), any(Instant.class));
    }

    @Test
    void retiringSnapshotRefusesNewPinsWithoutLease() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        when(repository.findActivePublicationSnapshot(7, 12))
                .thenReturn(Optional.of(new GraphSnapshotEntry(SNAPSHOT,
                        GraphSnapshotState.DELETING)));
        GraphSnapshotReadService service = service(repository);

        assertThatThrownBy(() -> service.pin(7, 12))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不再接受新的读取 pin");
        verify(repository, never()).acquireReadLease(anyLong(), any(Instant.class));
    }

    @Test
    void failedLeaseInsertMeansConcurrentRetirementWonTheRace() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        when(repository.findActivePublicationSnapshot(7, 12))
                .thenReturn(Optional.of(new GraphSnapshotEntry(SNAPSHOT,
                        GraphSnapshotState.READY)));
        when(repository.acquireReadLease(eq(91L), any(Instant.class))).thenReturn(0L);
        GraphSnapshotReadService service = service(repository);

        assertThatThrownBy(() -> service.pin(7, 12))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("读取租约获取失败");
    }

    @Test
    void multiKnowledgeBaseRequestsPinEachPairIndependently() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSnapshot second = new GraphSnapshot(92, 8, 43, 12, 3,
                "kwiki-chunks-v12", "kwiki-communities-v3-kb8", "entity-linking-v1",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 5, 2);
        when(repository.findActivePublicationSnapshot(7, 12))
                .thenReturn(Optional.of(new GraphSnapshotEntry(SNAPSHOT,
                        GraphSnapshotState.PUBLISHED)));
        when(repository.findActivePublicationSnapshot(8, 12))
                .thenReturn(Optional.empty());
        when(repository.acquireReadLease(eq(91L), any(Instant.class))).thenReturn(555L);
        GraphSnapshotReadService service = service(repository);

        Map<Long, Optional<GraphSnapshotPin>> pins =
                service.pinAll(Map.of(7L, 12L, 8L, 12L));

        assertThat(pins.get(7L)).isPresent();
        assertThat(pins.get(7L).orElseThrow().snapshot().kbId()).isEqualTo(7);
        assertThat(pins.get(8L)).isEmpty();
        verify(repository, never()).acquireReadLease(eq(92L), any(Instant.class));
    }

    @Test
    void releaseDropsLeaseIdempotently() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        when(repository.releaseReadLease(555L)).thenReturn(true);
        GraphSnapshotReadService service = service(repository);

        service.release(new GraphSnapshotPin(SNAPSHOT, 555L));
        service.release(null);

        verify(repository).releaseReadLease(555L);
    }

    private static GraphSnapshotReadService service(GraphBuildRepository repository) {
        GraphProperties properties = new GraphProperties(false,
                com.kwiki.graph.GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                "0 0 2 * * *", "Asia/Shanghai", false,
                java.time.Duration.ofMinutes(10), 2, java.time.Duration.ofMinutes(30),
                GraphProperties.Capacity.defaults());
        return new GraphSnapshotReadService(repository, properties);
    }
}
