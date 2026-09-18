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
 * 一张派生图片的持久身份：源（PDF 附件 / Markdown 上传图片 /
 * 镜像的外链图片）+ 解析器版本 + 图片内容哈希唯一确定一行。
 * 行内持有内容中心 contentId（上传成功后）与审计/清理状态；
 * 绝不保存 CDN URL、签名链接或任何凭据。摘要按
 * （asset, model, prompt-version）在 {@link DerivedImageSummary}
 * 中独立演进，提示词升级不重复上传同一份字节。
 */
@Entity
@Table(name = "derived_image_asset")
public class DerivedImageAsset {

    public static final String KIND_ATTACHMENT_PDF = "ATTACHMENT_PDF";
    public static final String KIND_ATTACHMENT_IMAGE = "ATTACHMENT_IMAGE";
    public static final String KIND_EXTERNAL_URL = "EXTERNAL_URL";

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_UPLOADED = "UPLOADED";
    public static final String STATE_FAILED = "FAILED";

    public static final String CLEANUP_NONE = "NONE";
    public static final String CLEANUP_ORPHAN_CANDIDATE = "ORPHAN_CANDIDATE";
    public static final String CLEANUP_DEFERRED = "CLEANUP_DEFERRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sourceKind;
    private String sourceRef;
    private long sourceKbId;
    private String parserVersion;

    @Column(columnDefinition = "CHAR(64)")
    private String imageSha256;

    /** 内容中心文件 id；上传确认前为 null。标记后必须为正数。 */
    private Long contentId;
    private String contentType;
    private Long byteSize;
    private String state = STATE_PENDING;
    private String cleanupState = CLEANUP_NONE;

    @Column(name = "source_url_hash", columnDefinition = "CHAR(64)")
    private String sourceUrlHash;

    @Version
    private Long lockVersion;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    protected DerivedImageAsset() {
    }

    public DerivedImageAsset(String sourceKind, String sourceRef, long sourceKbId,
                             String parserVersion, String imageSha256) {
        if (sourceKind == null || sourceKind.isBlank() || sourceRef == null
                || sourceRef.isBlank() || parserVersion == null || parserVersion.isBlank()
                || imageSha256 == null || imageSha256.length() != 64) {
            throw new IllegalArgumentException("derived image asset identity is incomplete");
        }
        this.sourceKind = sourceKind;
        this.sourceRef = sourceRef;
        this.sourceKbId = sourceKbId;
        this.parserVersion = parserVersion;
        this.imageSha256 = imageSha256;
        this.state = STATE_PENDING;
        this.cleanupState = CLEANUP_NONE;
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

    /** 上传成功后的状态转换：只接受经内容中心校验的正数 id。 */
    public void markUploaded(long contentId, String contentType, long byteSize) {
        if (contentId <= 0) {
            throw new IllegalArgumentException("content-center id must be positive");
        }
        if (!STATE_PENDING.equals(this.state) && this.contentId != null) {
            throw new IllegalStateException("asset already holds an authoritative content id");
        }
        this.contentId = contentId;
        this.contentType = contentType;
        this.byteSize = byteSize;
        this.state = STATE_UPLOADED;
        this.cleanupState = CLEANUP_NONE;
    }

    /** 上传阶段被判定为永久失败；保持可审计，等待人工/清理流程处置。 */
    public void markFailed() {
        this.state = STATE_FAILED;
    }

    public boolean hasAuthoritativeContent() {
        return contentId != null && contentId > 0 && STATE_UPLOADED.equals(state);
    }

    public void markOrphanCandidate() {
        if (CLEANUP_NONE.equals(this.cleanupState)) {
            this.cleanupState = CLEANUP_ORPHAN_CANDIDATE;
        }
    }

    public Long getId() {
        return id;
    }

    public String getSourceKind() {
        return sourceKind;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public long getSourceKbId() {
        return sourceKbId;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public String getImageSha256() {
        return imageSha256;
    }

    public Long getContentId() {
        return contentId;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getByteSize() {
        return byteSize;
    }

    public String getState() {
        return state;
    }

    public String getCleanupState() {
        return cleanupState;
    }

    public String getSourceUrlHash() {
        return sourceUrlHash;
    }

    public void assignSourceUrlHash(String sourceUrlHash) {
        this.sourceUrlHash = sourceUrlHash;
    }
}
