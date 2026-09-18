package com.kwiki.indexing.version;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchIndexRebuildRunWatermarkTest {

    @Test
    void eventReplayCursorAdvancesMonotonicallyAndResumesAtPersistedPosition() {
        SearchIndexRebuildRun run = SearchIndexRebuildRun.create(2, 1,
                RebuildRunKind.INITIAL, 1, "{}", "admin", "owner",
                Duration.ofMinutes(1), 10, Instant.EPOCH);
        run.complete(Instant.EPOCH);
        run.startSwitchPreparation(30, Instant.EPOCH);

        run.advanceReplayCursor(18);
        run.advanceReplayCursor(30);

        assertThat(run.getReplayEventId()).isEqualTo(30);
        assertThat(run.replayComplete()).isTrue();
        assertThatThrownBy(() -> run.advanceReplayCursor(29))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run.advanceReplayCursor(31))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void barrierKeepsPreparingUntilExplicitlyMarkedReady() {
        SearchIndexRebuildRun run = SearchIndexRebuildRun.create(2, 1,
                RebuildRunKind.INITIAL, 1, "{}", "admin", "owner",
                Duration.ofMinutes(1), 10, Instant.EPOCH);
        run.complete(Instant.EPOCH);
        run.startSwitchPreparation(20, Instant.EPOCH);
        run.advanceReplayCursor(20);

        run.captureCatchupBarrier(27, Instant.EPOCH);
        assertThat(run.switchState()).isEqualTo(IndexSwitchState.PREPARING);
        assertThat(run.getCatchupBarrierEventId()).isEqualTo(27);

        run.markSwitchReady(Instant.EPOCH);
        assertThat(run.switchState()).isEqualTo(IndexSwitchState.READY);
    }

    @Test
    void pauseResumeAndCancelRemainDurableAtBatchBoundaries() {
        SearchIndexRebuildRun run = SearchIndexRebuildRun.create(2, 1,
                RebuildRunKind.MANUAL, 1, "{}", "admin", "owner",
                Duration.ofMinutes(1), 10, Instant.EPOCH);

        run.requestPause(Instant.EPOCH);
        assertThat(run.state()).isEqualTo(RebuildRunState.PAUSED);
        assertThat(run.isPauseRequested()).isTrue();

        run.resume(Instant.EPOCH);
        assertThat(run.state()).isEqualTo(RebuildRunState.RUNNING);
        assertThat(run.isPauseRequested()).isFalse();

        run.requestCancel(Instant.EPOCH);
        assertThat(run.isCancelRequested()).isTrue();
        run.cancel(Instant.EPOCH);
        assertThat(run.state()).isEqualTo(RebuildRunState.CANCELLED);
        assertThat(run.getLeaseOwner()).isNull();
    }

    @Test
    void expiredLeaseCanBeFencedAndClaimedByARecoveryOwner() {
        SearchIndexRebuildRun run = SearchIndexRebuildRun.create(2, 1,
                RebuildRunKind.INITIAL, 1, "{}", "admin", "crashed-owner",
                Duration.ofSeconds(10), 0, Instant.EPOCH);

        assertThat(run.leaseActiveAt(Instant.EPOCH.plusSeconds(9))).isTrue();
        assertThat(run.leaseActiveAt(Instant.EPOCH.plusSeconds(11))).isFalse();

        run.claim("recovery-owner", Duration.ofMinutes(1), Instant.EPOCH.plusSeconds(11));
        assertThat(run.getLeaseOwner()).isEqualTo("recovery-owner");
        assertThat(run.state()).isEqualTo(RebuildRunState.RUNNING);
    }
}
