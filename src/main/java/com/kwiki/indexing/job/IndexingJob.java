package com.kwiki.indexing.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** 持久化的索引构建任务记录（indexing_job 表），带租约与重试元数据。 */
@Entity
@Table(name = "indexing_job")
public class IndexingJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String jobType;
    private String resourceType;
    private Long resourceId;
    private Long revisionId;
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    private IndexingJobState state = IndexingJobState.PENDING;

    private int attempts;
    private int maxAttempts = 8;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant nextAttemptAt;
    private String leaseOwner;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant leaseExpiresAt;
    private String lastErrorClass;
    private String lastErrorSummary;

    @Version
    private Long lockVersion;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    protected IndexingJob() {
    }

    public IndexingJob(String jobType, String resourceType, long resourceId,
                       Long revisionId, String idempotencyKey) {
        this.jobType = jobType;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.revisionId = revisionId;
        this.idempotencyKey = idempotencyKey;
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

    public String getJobType() {
        return jobType;
    }

    public String getResourceType() {
        return resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public Long getRevisionId() {
        return revisionId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public IndexingJobState getState() {
        return state;
    }

    /** 应用状态机，并记录租约/尝试次数的副作用。 */
    public void transitionTo(IndexingJobState next, String owner, Instant leaseExpiresAt,
                             Instant nextAttemptAt) {
        this.state = state.requireTransitionTo(next);
        this.leaseOwner = owner;
        this.leaseExpiresAt = leaseExpiresAt;
        this.nextAttemptAt = nextAttemptAt;
    }

    public void recordFailure(String errorClass, String sanitizedSummary, int maxAttempts,
                              Instant nextAttemptAt) {
        this.lastErrorClass = errorClass;
        this.lastErrorSummary = sanitizedSummary;
        this.maxAttempts = maxAttempts;
        this.nextAttemptAt = nextAttemptAt;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.state = state.requireTransitionTo(
                attempts + 1 >= maxAttempts ? IndexingJobState.FAILED : IndexingJobState.RETRY_WAIT);
    }

    public void recordSuccess() {
        this.state = state.requireTransitionTo(IndexingJobState.COMPLETED);
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.nextAttemptAt = null;
    }

    public int getAttempts() {
        return attempts;
    }

    public void incrementAttempts() {
        this.attempts++;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String getLastErrorClass() {
        return lastErrorClass;
    }

    public String getLastErrorSummary() {
        return lastErrorSummary;
    }
}
