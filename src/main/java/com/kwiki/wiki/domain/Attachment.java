package com.kwiki.wiki.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "attachment")
public class Attachment {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_STORED = "STORED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    public static final String PURPOSE_GENERAL = "GENERAL";
    public static final String PURPOSE_WIKI_IMPORT_SOURCE = "WIKI_IMPORT_SOURCE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Flyway 定义为 CHAR(36)；普通 String 会被校验为 VARCHAR(255)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String uuid;
    private Long kbId;
    private Long uploadedBy;
    private String fileName;
    private String contentType;
    private long byteSize;
    // 规范的内容中心文件 id；上传处于 PENDING 时为 null。这是唯一
    // 持久化的内容标识——存储密钥/来源保留在内容中心内部。
    private Long contentCenterFileId;
    private String status = STATUS_PENDING;
    private String purpose = PURPOSE_GENERAL;

    @Version
    private Long lockVersion;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    protected Attachment() {
    }

    public Attachment(String uuid, Long kbId, Long uploadedBy, String fileName,
                      String contentType, long byteSize) {
        this.uuid = uuid;
        this.kbId = kbId;
        this.uploadedBy = uploadedBy;
        this.fileName = fileName;
        this.contentType = contentType;
        this.byteSize = byteSize;
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

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public Long getContentCenterFileId() {
        return contentCenterFileId;
    }

    public String getStatus() {
        return status;
    }

    public boolean isStored() {
        return STATUS_STORED.equals(status);
    }

    /**
     * 将附件标记为 STORED。只有经过校验的正向内容中心文件 id 才能完成此次
     * 状态转换；缺少该 id 的 PENDING 行永远不可下载，也不可被索引。
     */
    public void markStored(long contentCenterFileId) {
        if (contentCenterFileId <= 0) {
            throw new IllegalArgumentException("content-center file id must be positive");
        }
        this.contentCenterFileId = contentCenterFileId;
        this.status = STATUS_STORED;
    }

    public void archive() {
        this.status = STATUS_ARCHIVED;
    }

    /** 回收站恢复：回到 STORED 状态，且已校验的文件 id 保持不变。 */
    public void restore() {
        this.status = STATUS_STORED;
    }

    public void markWikiImportSource() {
        this.purpose = PURPOSE_WIKI_IMPORT_SOURCE;
    }

    public String getPurpose() {
        return purpose;
    }
}
