package com.kwiki.indexing.version;

final class RebuildCancelledException extends RuntimeException {
    RebuildCancelledException(long runId) {
        super("rebuild run cancelled: " + runId);
    }
}
