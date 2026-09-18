package com.kwiki.indexing.version;

import java.time.Duration;
import java.util.Optional;

interface RebuildRunRegistry {

    Claim claimOrCreate(VersionRebuildCoordinator.Request request, String owner,
                        Duration leaseDuration);

    void complete(long runId, String owner);

    void fail(long runId, String owner, Throwable failure);

    void cancel(long runId, String owner);

    Optional<Long> activeRunId(int versionNumber);

    record Claim(SearchIndexRebuildRun run, boolean accepted, boolean resumed) {
        static Claim busy(SearchIndexRebuildRun run) {
            return new Claim(run, false, false);
        }

        static Claim accepted(SearchIndexRebuildRun run, boolean resumed) {
            return new Claim(run, true, resumed);
        }
    }
}
