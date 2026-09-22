package com.kwiki.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphSnapshotPinGuardTest {

    @Test
    void pinRejectsAliasAndCrossVersionMixing() {
        GraphSnapshot snapshot = new GraphSnapshot(91, 7, 4, 12, 3,
                "kwiki-chunks-v12", "kwiki-communities-v3-kb7", "entity-v1",
                "manifest", 1, 1);
        assertThatThrownBy(() -> GraphSnapshotPinGuard.pin(snapshot, 1, 11))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GraphSnapshotPinGuard.pin(new GraphSnapshot(91, 7, 4, 12, 3,
                "kwiki-chunks", "kwiki-communities-v3-kb7", "entity-v1",
                "manifest", 1, 1), 1, 12))
                .isInstanceOf(IllegalStateException.class);
    }
}
