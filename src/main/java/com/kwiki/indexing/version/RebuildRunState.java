package com.kwiki.indexing.version;

public enum RebuildRunState {
    PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED;

    public boolean active() {
        return this == PENDING || this == RUNNING || this == PAUSED;
    }
}
