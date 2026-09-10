package com.kwiki.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Immutable content revision: markdown source plus its plain-text projection.
 * Once persisted it is never mutated; restores create new revisions.
 */
@Entity
@Table(name = "wiki_page_revision")
public class WikiPageRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long pageId;
    private int revisionNo;
    // Flyway defines MEDIUMTEXT; plain String would validate as VARCHAR(255)
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String markdown;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String plainText;
    private String changeNote;
    private Long createdBy;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant publishedAt;

    protected WikiPageRevision() {
    }

    public WikiPageRevision(Long pageId, int revisionNo, String markdown,
                            String plainText, String changeNote, Long createdBy) {
        this.pageId = pageId;
        this.revisionNo = revisionNo;
        this.markdown = markdown;
        this.plainText = plainText;
        this.changeNote = changeNote;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPageId() {
        return pageId;
    }

    public int getRevisionNo() {
        return revisionNo;
    }

    public String getMarkdown() {
        return markdown;
    }

    public String getPlainText() {
        return plainText;
    }

    public String getChangeNote() {
        return changeNote;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() { return publishedAt; }

    public void markPublished() {
        if (publishedAt == null) publishedAt = Instant.now();
    }
}
