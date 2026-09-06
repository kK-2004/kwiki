package com.kwiki.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** Provenance record linking a page to the uploaded document it was derived from. */
@Entity
@Table(name = "source_document")
public class SourceDocument {

    public static final String REL_DERIVED_FROM = "DERIVED_FROM";
    public static final String REL_UPLOADED_SOURCE = "UPLOADED_SOURCE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long pageId;
    private Long attachmentId;
    private String relationship = REL_DERIVED_FROM;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    protected SourceDocument() {
    }

    public SourceDocument(Long pageId, Long attachmentId, String relationship) {
        this.pageId = pageId;
        this.attachmentId = attachmentId;
        this.relationship = relationship;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getPageId() {
        return pageId;
    }

    public Long getAttachmentId() {
        return attachmentId;
    }

    public String getRelationship() {
        return relationship;
    }
}
