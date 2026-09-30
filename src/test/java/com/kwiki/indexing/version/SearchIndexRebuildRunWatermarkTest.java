package com.kwiki.indexing.version;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SearchIndexRebuildRunWatermarkTest {

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
