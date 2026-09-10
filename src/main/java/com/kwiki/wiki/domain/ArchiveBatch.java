package com.kwiki.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One recoverable recycle-bin batch: the archived root (a page subtree or a
 * knowledge base), its operator, the exact retention window, and the ES index
 * sync status. Retrying the same archive request reuses the existing batch and
 * never resets the timer.
 */
@Entity
@Table(name = "archive_batch")
public class ArchiveBatch {

    public static final String SCOPE_PAGE = "PAGE";
    public static final String SCOPE_KNOWLEDGE_BASE = "KNOWLEDGE_BASE";
    public static final String STATE_ARCHIVED = "ARCHIVED";
    public static final String STATE_RESTORED = "RESTORED";
    public static final String STATE_PURGED = "PURGED";
    public static final String SYNC_PENDING = "PENDING";
    public static final String SYNC_SYNCED = "SYNCED";
    public static final String ORIGIN_NORMAL = "NORMAL";
    public static final String ORIGIN_LEGACY_BACKFILL = "LEGACY_BACKFILL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    private String batchUuid;
    private String scopeType;
    private long rootResourceId;
    private long kbId;
    private long operatorId;
    @Column(columnDefinition = "DATETIME(6)", nullable = false)
    private Instant archivedAt;
    @Column(columnDefinition = "DATETIME(6)", nullable = false)
    private Instant purgeAfter;
    private String state = STATE_ARCHIVED;
    private String indexSyncStatus = SYNC_PENDING;
    private String origin = ORIGIN_NORMAL;
    private int itemCount;

    @Version
    private Long lockVersion;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    protected ArchiveBatch() {
    }

    public ArchiveBatch(String batchUuid, String scopeType, long rootResourceId, long kbId,
                        long operatorId, Instant archivedAt, Instant purgeAfter,
                        int itemCount, String origin) {
        this.batchUuid = batchUuid;
        this.scopeType = scopeType;
        this.rootResourceId = rootResourceId;
        this.kbId = kbId;
        this.operatorId = operatorId;
        this.archivedAt = archivedAt;
        this.purgeAfter = purgeAfter;
        this.itemCount = itemCount;
        this.origin = origin;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getBatchUuid() {
        return batchUuid;
    }

    public String getScopeType() {
        return scopeType;
    }

    public long getRootResourceId() {
        return rootResourceId;
    }

    public long getKbId() {
        return kbId;
    }

    public long getOperatorId() {
        return operatorId;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public Instant getPurgeAfter() {
        return purgeAfter;
    }

    public String getState() {
        return state;
    }

    public void markRestored() {
        this.state = STATE_RESTORED;
    }

    public void markPurged() {
        this.state = STATE_PURGED;
    }

    public String getIndexSyncStatus() {
        return indexSyncStatus;
    }

    public void markIndexSynced() {
        this.indexSyncStatus = SYNC_SYNCED;
    }

    public String getOrigin() {
        return origin;
    }

    public int getItemCount() {
        return itemCount;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(purgeAfter);
    }

    public boolean isArchivedState() {
        return STATE_ARCHIVED.equals(state);
    }
}
