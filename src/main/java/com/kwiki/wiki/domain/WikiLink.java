package com.kwiki.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** 两个页面之间有向链接，由 Markdown 链接解析为稳定的页面 id。 */
@Entity
@Table(name = "wiki_link")
public class WikiLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sourcePageId;
    private Long targetPageId;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    protected WikiLink() {
    }

    public WikiLink(Long sourcePageId, Long targetPageId) {
        this.sourcePageId = sourcePageId;
        this.targetPageId = targetPageId;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getSourcePageId() {
        return sourcePageId;
    }

    public Long getTargetPageId() {
        return targetPageId;
    }
}
