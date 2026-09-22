package com.kwiki.graph.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphSnapshotWriteGuardTest {

    @Test
    void onlyCurrentBuildingWorkerMayWrite() {
        assertThatCode(() -> GraphSnapshotWriteGuard.requireWritable(
                GraphSnapshotState.BUILDING, 11, 11, 7, 7)).doesNotThrowAnyException();
        assertThatThrownBy(() -> GraphSnapshotWriteGuard.requireWritable(
                GraphSnapshotState.READY, 11, 11, 7, 7))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GraphSnapshotWriteGuard.requireWritable(
                GraphSnapshotState.BUILDING, 11, 12, 7, 7))
                .isInstanceOf(IllegalStateException.class);
    }
}
