package com.kwiki.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** 保存在不可变已发布修订历史之外的可变工作副本。 */
@Entity
@Table(name = "wiki_page_draft")
public class WikiPageDraft {

    @Id
    private Long pageId;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String markdown;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String plainText;
    private String changeNote;
    private Long updatedBy;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    protected WikiPageDraft() {
    }

    public WikiPageDraft(Long pageId, String markdown, String plainText,
                         String changeNote, Long updatedBy) {
        this.pageId = pageId;
        replace(markdown, plainText, changeNote, updatedBy);
    }

    public void replace(String markdown, String plainText, String changeNote, Long updatedBy) {
        this.markdown = markdown;
        this.plainText = plainText;
        this.changeNote = changeNote;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    @PreUpdate
    void touch() {
        if (updatedAt == null) updatedAt = Instant.now();
    }

    public Long getPageId() { return pageId; }
    public String getMarkdown() { return markdown; }
    public String getPlainText() { return plainText; }
    public String getChangeNote() { return changeNote; }
    public Long getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
