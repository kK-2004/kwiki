package com.kwiki.indexing.version;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "search_index_rebuild_range")
public class SearchIndexRebuildRange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long runId;
    private String resourceType;
    private long minId;
    private long maxId;
    private long lastSeenId;
    private long tailLastSeenId;
    private Long tailMaxId;
    private long itemsScanned;
    private long itemsSucceeded;
    private long itemsSkipped;
    private long itemsFailed;
    @Version
    private long lockVersion;

    protected SearchIndexRebuildRange() { }

    SearchIndexRebuildRange(long runId, String resourceType, long minId, long maxId) {
        this.runId = runId;
        this.resourceType = resourceType;
        this.minId = minId;
        this.maxId = maxId;
        this.lastSeenId = minId - 1;
        this.tailLastSeenId = minId - 1;
    }

    public long getRunId() { return runId; }
    public long id() { return id; }
    public String getResourceType() { return resourceType; }
    public long getMinId() { return minId; }
    public long getMaxId() { return maxId; }
    public long getLastSeenId() { return lastSeenId; }
    public long getTailLastSeenId() { return tailLastSeenId; }
    public Long getTailMaxId() { return tailMaxId; }

    void captureTailUpperBound(long upperBound) {
        tailLastSeenId = Math.max(tailLastSeenId, maxId);
        tailMaxId = Math.max(upperBound, maxId);
    }

    void recordTailBatch(long newCursor, long scanned) {
        if (tailMaxId == null) {
            throw new IllegalStateException("tail upper bound has not been captured");
        }
        if (newCursor < tailLastSeenId || newCursor > tailMaxId) {
            throw new IllegalArgumentException("tail cursor must move forward within bounds");
        }
        tailLastSeenId = newCursor;
        itemsScanned += scanned;
    }

    public boolean tailComplete() {
        return tailMaxId != null && tailLastSeenId >= tailMaxId;
    }

    void recordBatch(long newCursor, long scanned, long succeeded, long skipped, long failed) {
        if (newCursor < lastSeenId || newCursor > maxId) {
            throw new IllegalArgumentException("range cursor must move forward within bounds");
        }
        lastSeenId = newCursor;
        itemsScanned += scanned;
        itemsSucceeded += succeeded;
        itemsSkipped += skipped;
        itemsFailed += failed;
    }

    void setTargetResults(long succeeded, long failed) {
        this.itemsSucceeded = succeeded;
        this.itemsFailed = failed;
    }

    public boolean baselineComplete() { return lastSeenId >= maxId; }
    public long getItemsScanned() { return itemsScanned; }
    public long getItemsSucceeded() { return itemsSucceeded; }
    public long getItemsSkipped() { return itemsSkipped; }
    public long getItemsFailed() { return itemsFailed; }
}
