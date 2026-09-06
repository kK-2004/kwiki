package com.kwiki.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "wiki_tag")
public class WikiTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long kbId;
    private String name;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    protected WikiTag() {
    }

    public WikiTag(Long kbId, String name) {
        this.kbId = kbId;
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getKbId() {
        return kbId;
    }

    public String getName() {
        return name;
    }
}
