package com.kwiki.indexing.version;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "search_index_rebuild_run")
public class SearchIndexRebuildRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private int versionNumber;
    private long buildGeneration;
    private String runKind;
    private long configRevision;
    @Column(columnDefinition = "MEDIUMTEXT")
    private String buildManifest;
    private String state;
    private long buildStartEventId;
    private long replayEventId;
    private Long dualWriteStartEventId;
    private Long catchupBarrierEventId;
    private String switchState = IndexSwitchState.NONE.name();
    private long resourcesScanned;
    private long resourcesSucceeded;
    private long resourcesSkipped;
    private long resourcesFailed;
    private String leaseOwner;
    private Instant leaseExpiresAt;
    private boolean pauseRequested;
    private boolean cancelRequested;
    private String errorClass;
    private String errorSummary;
    private String requestedBy;
    private Instant startedAt;
    private Instant completedAt;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private long lockVersion;

    protected SearchIndexRebuildRun() {
    }

    static SearchIndexRebuildRun create(int versionNumber, long buildGeneration,
                                        RebuildRunKind kind, long configRevision,
                                        String manifest, String requestedBy,
                                        String leaseOwner, Duration leaseDuration,
                                        long buildStartEventId, Instant now) {
        SearchIndexRebuildRun run = new SearchIndexRebuildRun();
        run.versionNumber = versionNumber;
        run.buildGeneration = buildGeneration;
        run.runKind = kind.name();
        run.configRevision = configRevision;
        run.buildManifest = manifest;
        run.buildStartEventId = buildStartEventId;
        run.replayEventId = buildStartEventId;
        run.state = RebuildRunState.RUNNING.name();
        run.requestedBy = requestedBy;
        run.startedAt = now;
        run.createdAt = now;
        run.updatedAt = now;
        run.claim(leaseOwner, leaseDuration, now);
        return run;
    }

    void claim(String owner, Duration duration, Instant now) {
        leaseOwner = owner;
        leaseExpiresAt = now.plus(duration);
        state = pauseRequested ? RebuildRunState.PAUSED.name() : RebuildRunState.RUNNING.name();
        updatedAt = now;
    }

    void complete(Instant now) {
        state = RebuildRunState.COMPLETED.name();
        leaseOwner = null;
        leaseExpiresAt = null;
        completedAt = now;
        updatedAt = now;
    }

    void fail(Throwable failure, Instant now) {
        state = RebuildRunState.FAILED.name();
        leaseOwner = null;
        leaseExpiresAt = null;
        completedAt = now;
        errorClass = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        errorSummary = message == null ? errorClass
                : message.substring(0, Math.min(message.length(), 1000));
        updatedAt = now;
    }

    public Long getId() { return id; }
    public int getVersionNumber() { return versionNumber; }
    public long getBuildGeneration() { return buildGeneration; }
    public RebuildRunKind kind() { return RebuildRunKind.valueOf(runKind); }
    public long getConfigRevision() { return configRevision; }
    public String getBuildManifest() { return buildManifest; }
    public long getBuildStartEventId() { return buildStartEventId; }
    public long getReplayEventId() { return replayEventId; }
    public Long getDualWriteStartEventId() { return dualWriteStartEventId; }
    public Long getCatchupBarrierEventId() { return catchupBarrierEventId; }
    public IndexSwitchState switchState() { return IndexSwitchState.valueOf(switchState); }
    public RebuildRunState state() { return RebuildRunState.valueOf(state); }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public long getLockVersion() { return lockVersion; }
    public boolean isPauseRequested() { return pauseRequested; }
    public boolean isCancelRequested() { return cancelRequested; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getErrorClass() { return errorClass; }
    public String getErrorSummary() { return errorSummary; }

    void recordBatch(long scanned, long succeeded, long skipped, long failed) {
        resourcesScanned += scanned;
        resourcesSucceeded += succeeded;
        resourcesSkipped += skipped;
        resourcesFailed += failed;
    }

    void setTargetResults(long succeeded, long failed) {
        this.resourcesSucceeded = succeeded;
        this.resourcesFailed = failed;
    }

    public long getResourcesScanned() { return resourcesScanned; }
    public long getResourcesSucceeded() { return resourcesSucceeded; }
    public long getResourcesSkipped() { return resourcesSkipped; }
    public long getResourcesFailed() { return resourcesFailed; }

    void startSwitchPreparation(long eventId, Instant now) {
        if (state() != RebuildRunState.COMPLETED) {
            throw new IllegalStateException("baseline rebuild is not complete");
        }
        if (eventId < buildStartEventId) {
            throw new IllegalArgumentException("dual-write watermark precedes build watermark");
        }
        dualWriteStartEventId = eventId;
        catchupBarrierEventId = null;
        switchState = IndexSwitchState.PREPARING.name();
        updatedAt = now;
    }

    void advanceReplayCursor(long eventId) {
        if (dualWriteStartEventId == null) {
            throw new IllegalStateException("switch preparation has not started");
        }
        if (eventId < replayEventId || eventId > dualWriteStartEventId) {
            throw new IllegalArgumentException("event replay cursor is outside its watermarks");
        }
        replayEventId = eventId;
    }

    public boolean replayComplete() {
        return dualWriteStartEventId != null && replayEventId >= dualWriteStartEventId;
    }

    void captureCatchupBarrier(long eventId, Instant now) {
        if (switchState() != IndexSwitchState.PREPARING || !replayComplete()) {
            throw new IllegalStateException("switch catch-up is not ready for a barrier");
        }
        if (eventId < dualWriteStartEventId) {
            throw new IllegalArgumentException("catch-up barrier precedes dual-write watermark");
        }
        if (catchupBarrierEventId == null) {
            catchupBarrierEventId = eventId;
            updatedAt = now;
        }
    }

    void markSwitchReady(Instant now) {
        if (catchupBarrierEventId == null) {
            throw new IllegalStateException("catch-up barrier has not been captured");
        }
        switchState = IndexSwitchState.READY.name();
        updatedAt = now;
    }

    void requestPause(Instant now) {
        if (!state().active()) throw new IllegalStateException("terminal run cannot be paused");
        pauseRequested = true;
        state = RebuildRunState.PAUSED.name();
        updatedAt = now;
    }

    void resume(Instant now) {
        if (state() != RebuildRunState.PAUSED) {
            throw new IllegalStateException("only a paused run can be resumed");
        }
        pauseRequested = false;
        state = RebuildRunState.RUNNING.name();
        updatedAt = now;
    }

    void requestCancel(Instant now) {
        if (!state().active()) throw new IllegalStateException("terminal run cannot be cancelled");
        cancelRequested = true;
        pauseRequested = false;
        updatedAt = now;
    }

    void cancel(Instant now) {
        state = RebuildRunState.CANCELLED.name();
        leaseOwner = null;
        leaseExpiresAt = null;
        completedAt = now;
        updatedAt = now;
    }

    boolean leaseActiveAt(Instant instant) {
        return leaseExpiresAt != null && leaseExpiresAt.isAfter(instant);
    }
}
