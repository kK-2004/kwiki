package com.kwiki.indexing.version;

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
 * 一个物理索引版本的持久化行（V21）。版本号单调分配且不复用：删除只
 * 置 tombstone（deletedAt），行永不物理消失。dirty 是
 * configRevision/builtConfigRevision 的派生事实，没有布尔列。凭据绝不
 * 入库：embeddingProvider 只保存档案名。
 */
@Entity
@Table(name = "search_index_version")
public class SearchIndexVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int versionNumber;
    private String physicalName;
    private String parserVersion;
    private String chunkerVersion;
    private String embeddingProvider;
    private String embeddingModel;
    private int embeddingDimensions;
    private int mappingSchemaVersion;
    private long configRevision = 1;
    private Long builtConfigRevision;
    private String buildState = IndexBuildState.NEW.name();
    private String catchupStatus = IndexCatchupStatus.BEHIND.name();
    private boolean writeEnabled;
    private boolean adminDisabled;
    private boolean selected;
    private boolean pipelineSupported = true;
    /** SHA-256 十六进制摘要，精确匹配 V21 的定长 CHAR(64)。 */
    @Column(length = 64, columnDefinition = "CHAR(64)")
    private String mappingHash;
    private String healthSummary;
    private String needsAttentionReason;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant lastValidationAt;
    @Column(columnDefinition = "MEDIUMTEXT")
    private String validationSummary;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant deletedAt;
    @Version
    private Long lockVersion;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant createdAt;
    @Column(columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    protected SearchIndexVersion() {
    }

    SearchIndexVersion(int versionNumber, String physicalName, EditableIndexConfig config,
                       String mappingHash) {
        this.versionNumber = versionNumber;
        this.physicalName = physicalName;
        this.parserVersion = config.parserVersion();
        this.chunkerVersion = config.chunkerVersion();
        this.embeddingProvider = config.embeddingProvider();
        this.embeddingModel = config.embeddingModel();
        this.embeddingDimensions = config.embeddingDimensions();
        this.mappingSchemaVersion = config.mappingSchemaVersion();
        this.mappingHash = mappingHash;
    }

    /** 首次部署引导 v1：configRevision == builtConfigRevision 的已构建配置。 */
    public static SearchIndexVersion bootstrapped(int versionNumber, String physicalName,
                                                  EditableIndexConfig config, String mappingHash) {
        SearchIndexVersion entity = new SearchIndexVersion(versionNumber, physicalName,
                config, mappingHash);
        entity.configRevision = 1;
        entity.builtConfigRevision = 1L;
        entity.buildState = IndexBuildState.BUILT.name();
        entity.catchupStatus = IndexCatchupStatus.CURRENT.name();
        entity.writeEnabled = true;
        entity.selected = true;
        return entity;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getPhysicalName() {
        return physicalName;
    }

    public long getConfigRevision() {
        return configRevision;
    }

    public Long getBuiltConfigRevision() {
        return builtConfigRevision;
    }

    public String getBuildState() {
        return buildState;
    }

    public String getCatchupStatus() {
        return catchupStatus;
    }

    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    public boolean isAdminDisabled() {
        return adminDisabled;
    }

    public boolean isSelected() {
        return selected;
    }

    public boolean isPipelineSupported() {
        return pipelineSupported;
    }

    public String getMappingHash() {
        return mappingHash;
    }

    public String getNeedsAttentionReason() {
        return needsAttentionReason;
    }

    public String getHealthSummary() { return healthSummary; }
    public Instant getLastValidationAt() { return lastValidationAt; }
    public String getValidationSummary() { return validationSummary; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    void recordValidation(String summary, Instant now) {
        validationSummary = summary;
        lastValidationAt = now;
    }

    public EditableIndexConfig editableConfig() {
        return new EditableIndexConfig(parserVersion, chunkerVersion, embeddingProvider,
                embeddingModel, embeddingDimensions, mappingSchemaVersion);
    }

    /** 无凭据的不可变构建快照：run 开始时固化，worker 只使用该快照。 */
    public BuildManifestSnapshot buildManifestSnapshot() {
        return new BuildManifestSnapshot(versionNumber, physicalName, configRevision,
                parserVersion, chunkerVersion, embeddingProvider, embeddingModel,
                embeddingDimensions, mappingSchemaVersion, mappingHash);
    }

    /**
     * 编辑离线版本配置：原子递增 configRevision 并刷新派生 mappingHash。
     * 编辑限制由 IndexVersionStatusPolicy.editable() 在服务层先校验。
     */
    public void applyConfigEdit(EditableIndexConfig config, String newMappingHash) {
        this.parserVersion = config.parserVersion();
        this.chunkerVersion = config.chunkerVersion();
        this.embeddingProvider = config.embeddingProvider();
        this.embeddingModel = config.embeddingModel();
        this.embeddingDimensions = config.embeddingDimensions();
        this.mappingSchemaVersion = config.mappingSchemaVersion();
        this.mappingHash = newMappingHash;
        this.configRevision++;
    }

    /** 应用策略层推导出的状态迁移结果（除配置六元组与修订外的全部状态列）。 */
    public void applySnapshot(IndexVersionSnapshot snapshot) {
        this.buildState = snapshot.buildState().name();
        this.catchupStatus = snapshot.catchupStatus().name();
        this.writeEnabled = snapshot.writeEnabled();
        this.selected = snapshot.selected();
        this.pipelineSupported = snapshot.pipelineSupported();
        this.needsAttentionReason = snapshot.needsAttentionReason();
        this.builtConfigRevision = snapshot.builtConfigRevision();
    }

    /** 物理索引删除后的 tombstone：版本号从此不再复用。 */
    public void tombstone() {
        this.deletedAt = Instant.now();
        this.writeEnabled = false;
        this.selected = false;
    }

    /** 切换准备加入未来事件多写；管理员明确停用的版本不得被隐式恢复。 */
    void enableForSwitchPreparation() {
        if (deletedAt != null || adminDisabled || !pipelineSupported) {
            throw new IllegalStateException("version " + versionNumber
                    + " is not eligible for switch-preparation writes");
        }
        writeEnabled = true;
    }

    /** 显式停用事实独立于临时 writeEnabled，避免后续切换准备误恢复。 */
    void disableByAdministrator() {
        if (selected) {
            throw new IllegalStateException("selected version cannot be disabled");
        }
        adminDisabled = true;
        writeEnabled = false;
        catchupStatus = IndexCatchupStatus.BEHIND.name();
    }

    /** 重新纳入未来事件写入；历史缺口必须由随后的切换准备补齐。 */
    void reenableByAdministrator() {
        if (deletedAt != null) {
            throw new IllegalStateException("deleted version cannot be re-enabled");
        }
        if (!pipelineSupported) {
            throw new IllegalStateException("unsupported version cannot be re-enabled");
        }
        adminDisabled = false;
        writeEnabled = true;
        catchupStatus = IndexCatchupStatus.BEHIND.name();
    }

    /**
     * 遗留别名目标无法安全匹配时：仍按别名事实保持 selected（读路径
     * 不受影响），但管理端因 NEEDS_ATTENTION 失败关闭，且该状态不可
     * 声明为受支持流水线。
     */
    public void markUnmatchedLegacyState(String sanitizedReason) {
        this.needsAttentionReason = sanitizedReason;
        this.pipelineSupported = false;
        this.healthSummary = "NEEDS_ATTENTION";
        this.catchupStatus = IndexCatchupStatus.BEHIND.name();
        this.writeEnabled = false;
    }

    /** 供快照映射使用（活动 run / 切换准备标志来自 run 表，非本表事实）。 */
    public IndexVersionSnapshot toSnapshot(boolean activeRun, boolean switchPreparing) {
        return new IndexVersionSnapshot(versionNumber, physicalName, configRevision,
                builtConfigRevision, IndexBuildState.valueOf(buildState),
                IndexCatchupStatus.valueOf(catchupStatus), writeEnabled, selected,
                pipelineSupported, deletedAt != null, needsAttentionReason, activeRun,
                switchPreparing);
    }
}
