package com.kwiki.wiki.domain;

import com.kwiki.wiki.access.KnowledgeBaseRole;
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

@Entity
@Table(name = "knowledge_base_member")
public class KnowledgeBaseMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long kbId;
    private Long userId;

    @Enumerated(EnumType.STRING)
    private KnowledgeBaseRole role;

    private Long createdBy;

    @Version
    private Long lockVersion;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    protected KnowledgeBaseMember() {
    }

    public KnowledgeBaseMember(Long kbId, Long userId, KnowledgeBaseRole role, Long createdBy) {
        this.kbId = kbId;
        this.userId = userId;
        this.role = role;
        this.createdBy = createdBy;
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

    public Long getKbId() {
        return kbId;
    }

    public Long getUserId() {
        return userId;
    }

    public KnowledgeBaseRole getRole() {
        return role;
    }

    public void changeRole(KnowledgeBaseRole newRole) {
        this.role = newRole;
    }
}
