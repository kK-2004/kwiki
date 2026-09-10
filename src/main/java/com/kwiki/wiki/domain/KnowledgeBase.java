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

@Entity
@Table(name = "knowledge_base")
public class KnowledgeBase {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Flyway defines CHAR(36); a plain String would validate as VARCHAR(255)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String uuid;
    private String name;
    private String description;
    private String status = STATUS_ACTIVE;
    private long lifecycleVersion = 1L;
    private Long createdBy;
    private Long ownerId;

    @Version
    private Long lockVersion;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    protected KnowledgeBase() {
    }

    public KnowledgeBase(String uuid, String name, String description, Long createdBy) {
        this.uuid = uuid;
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
        this.ownerId = createdBy;
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

    public String getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void update(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public void archive() {
        this.status = STATUS_ARCHIVED;
    }

    public void restore() {
        this.status = STATUS_ACTIVE;
    }

    public boolean isArchived() {
        return STATUS_ARCHIVED.equals(status);
    }

    public long getLifecycleVersion() {
        return lifecycleVersion;
    }

    /** Every lifecycle transition (archive/restore) advances the fencing version. */
    public void bumpLifecycleVersion() {
        this.lifecycleVersion++;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Long getOwnerId() {
        return ownerId;
    }
}
