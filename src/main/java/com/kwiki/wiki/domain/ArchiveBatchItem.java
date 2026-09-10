package com.kwiki.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Exact snapshot of one object inside an archive batch: resource identity, the
 * pre-archive parent, the lifecycle version at archive time (restore only acts
 * on items whose version still matches), and the physical-purge marker used by
 * the daily cleanup.
 */
@Entity
@Table(name = "archive_batch_item")
public class ArchiveBatchItem {

    public static final String RESOURCE_PAGE = "PAGE";
    public static final String RESOURCE_KNOWLEDGE_BASE = "KNOWLEDGE_BASE";
    public static final String RESOURCE_ATTACHMENT = "ATTACHMENT";
    public static final String PRIOR_ACTIVE = "ACTIVE";
    public static final String PRIOR_ARCHIVED = "ARCHIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long batchId;
    private String resourceType;
    private long resourceId;
    private long kbId;
    private String priorState;
    private Long priorParentId;
    private long lifecycleVersion;
    private boolean purged;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant purgedAt;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    protected ArchiveBatchItem() {
    }

    public ArchiveBatchItem(long batchId, String resourceType, long resourceId, long kbId,
                            String priorState, Long priorParentId, long lifecycleVersion) {
        this.batchId = batchId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.kbId = kbId;
        this.priorState = priorState;
        this.priorParentId = priorParentId;
        this.lifecycleVersion = lifecycleVersion;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public long getBatchId() {
        return batchId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public long getResourceId() {
        return resourceId;
    }

    public long getKbId() {
        return kbId;
    }

    public String getPriorState() {
        return priorState;
    }

    public Long getPriorParentId() {
        return priorParentId;
    }

    public long getLifecycleVersion() {
        return lifecycleVersion;
    }

    public boolean isPurged() {
        return purged;
    }

    public void markPurged() {
        this.purged = true;
        this.purgedAt = Instant.now();
    }
}
