package com.kwiki.wiki.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Tree node of a knowledge base (FOLDER or PAGE) with stable id, ordered siblings,
 * archive state, and pointers to the current draft/published revisions. Parent and
 * revision references are kept as scalar ids; cycle and same-knowledge-base rules
 * are enforced by WikiTreeService before any move.
 */
@Entity
@Table(name = "wiki_page")
public class WikiPage {

    public static final String TYPE_FOLDER = "FOLDER";
    public static final String TYPE_PAGE = "PAGE";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Flyway defines CHAR(36); a plain String would validate as VARCHAR(255)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String uuid;
    private Long kbId;
    private Long parentId;
    private String title;
    private String nodeType = TYPE_PAGE;
    private int siblingOrder;
    private String status = STATUS_ACTIVE;
    private Long currentDraftRevisionId;
    private Long currentPublishedRevisionId;
    private Long createdBy;
    private Long ownerId;

    @Version
    private Long lockVersion;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    protected WikiPage() {
    }

    public WikiPage(String uuid, Long kbId, Long parentId, String title, String nodeType,
                    int siblingOrder, Long createdBy) {
        this.uuid = uuid;
        this.kbId = kbId;
        this.parentId = parentId;
        this.title = title;
        this.nodeType = nodeType;
        this.siblingOrder = siblingOrder;
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

    public Long getKbId() {
        return kbId;
    }

    public Long getParentId() {
        return parentId;
    }

    public String getTitle() {
        return title;
    }

    public void rename(String newTitle) {
        this.title = newTitle;
    }

    public String getNodeType() {
        return nodeType;
    }

    public boolean isFolder() {
        return TYPE_FOLDER.equals(nodeType);
    }

    public int getSiblingOrder() {
        return siblingOrder;
    }

    public void moveTo(Long newParentId, int newSiblingOrder) {
        this.parentId = newParentId;
        this.siblingOrder = newSiblingOrder;
    }

    public String getStatus() {
        return status;
    }

    public void archive() {
        this.status = STATUS_ARCHIVED;
    }

    public boolean isArchived() {
        return STATUS_ARCHIVED.equals(status);
    }

    public Long getCurrentDraftRevisionId() {
        return currentDraftRevisionId;
    }

    public void setCurrentDraftRevisionId(Long revisionId) {
        this.currentDraftRevisionId = revisionId;
    }

    public Long getCurrentPublishedRevisionId() {
        return currentPublishedRevisionId;
    }

    public void setCurrentPublishedRevisionId(Long revisionId) {
        this.currentPublishedRevisionId = revisionId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public int getLockVersion() {
        return lockVersion == null ? 0 : lockVersion.intValue();
    }
}
