package com.kwiki.indexing.multimodal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 一张派生图片在特定（model, prompt-version）下的视觉摘要。
 * 摘要文本在 READY 前不可被索引引用；并发竞争由唯一键与
 * 乐观锁收敛——只有一个执行者的结果成为权威。
 */
@Entity
@Table(name = "derived_image_summary")
public class DerivedImageSummary {

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_READY = "READY";
    public static final String STATE_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long assetId;
    private String model;
    private String promptVersion;
    private String state = STATE_PENDING;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String summary;

    @Version
    private Long lockVersion;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    protected DerivedImageSummary() {
    }

    public DerivedImageSummary(Long assetId, String model, String promptVersion) {
        if (assetId == null || assetId <= 0 || model == null || model.isBlank()
                || promptVersion == null || promptVersion.isBlank()) {
            throw new IllegalArgumentException("derived image summary identity is incomplete");
        }
        this.assetId = assetId;
        this.model = model;
        this.promptVersion = promptVersion;
        this.state = STATE_PENDING;
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

    /** 摘要成功：非空文本 + READY 一次性写入；已完成行不可被覆盖。 */
    public void markReady(String summary) {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        if (STATE_READY.equals(this.state) && this.summary != null) {
            throw new IllegalStateException("summary already holds an authoritative result");
        }
        this.summary = summary;
        this.state = STATE_READY;
    }

    public void markFailed() {
        this.state = STATE_FAILED;
    }

    public boolean isUsable() {
        return STATE_READY.equals(state) && summary != null && !summary.isBlank();
    }

    public Long getId() {
        return id;
    }

    public Long getAssetId() {
        return assetId;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getState() {
        return state;
    }

    public String getSummary() {
        return summary;
    }
}
