package com.kwiki.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "wiki_page_tag")
@IdClass(WikiPageTag.PageTagKey.class)
public class WikiPageTag {

    @Id
    private Long pageId;

    @Id
    private Long tagId;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    protected WikiPageTag() {
    }

    public WikiPageTag(Long pageId, Long tagId) {
        this.pageId = pageId;
        this.tagId = tagId;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getPageId() {
        return pageId;
    }

    public Long getTagId() {
        return tagId;
    }

    /** 页面-标签对的复合主键。 */
    public record PageTagKey(Long pageId, Long tagId) implements Serializable {
    }
}
