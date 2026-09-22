package com.kwiki.graph.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 图批次、租约、检查点和发布配对的权威仓储边界。 */
public interface GraphBuildRepository {

    Optional<Long> findBatchByIdempotencyKey(String idempotencyKey);

    Optional<Long> findScheduledBatch(LocalDate scheduleDate);

    long allocateCommunityIndexVersion(String physicalNamePattern, int mappingSchemaVersion,
                                       long configRevision);

    long allocateGraphVersion(long kbId);

    long createBatch(GraphBuildBatchCommand command);

    long createRun(GraphBuildRunCommand command);

    void bindCommunityVersionToBatch(long communityIndexVersion, long batchId);

    Optional<GraphBuildRunRecord> findActiveRunForUpdate(long kbId);

    /** 检查 Chunk 物理版本是否仍被任一活动图子任务固定引用。 */
    default boolean hasActiveRunReferencingChunkIndex(int chunkIndexVersion) {
        return false;
    }

    boolean acquireLease(long runId, String owner, Duration leaseDuration, long expectedFencingToken);

    boolean renewLease(long runId, String owner, long fencingToken, Duration leaseDuration);

    boolean checkpoint(long runId, String owner, long fencingToken,
                       GraphBuildState state, GraphBuildStage stage, long eventWatermark);

    Optional<GraphPublicationRecord> findPublication(long kbId, int chunkIndexVersion);

    boolean compareAndSetPublication(long kbId, int chunkIndexVersion, Long expectedSnapshotId,
                                     long nextSnapshotId, long contentEpoch, long securityEpoch,
                                     Instant publishedAt);

    /** 仅当报告通过时把 BUILDING 快照封存为 READY，同时持久化校验报告。 */
    boolean sealSnapshotReady(long snapshotId, String validationJson);

    /** 持久化失败报告用于诊断；不改变快照状态，候选保持可恢复。 */
    boolean persistValidationReport(long snapshotId, String validationJson);

    Optional<String> findSnapshotValidationJson(long snapshotId);

    /** 读取 run 所属批次提交时固定的 autoPublish 标志；运行期间不随全局配置漂移。 */
    boolean findBatchAutoPublishForRun(long runId);

    /** 读取发布指针当前指向的快照及状态；请求固定配对的唯一入口。 */
    Optional<GraphSnapshotEntry> findActivePublicationSnapshot(long kbId, int chunkIndexVersion);

    /** 原子获取读取租约：仅当快照仍处于 READY/PUBLISHED 时插入，否则返回 0。 */
    long acquireReadLease(long snapshotId, Instant expiresAt);

    boolean releaseReadLease(long leaseId);

    /** 退役清理使用：是否仍有未到期的读取租约。 */
    boolean hasActiveReadLeases(long snapshotId);

    Optional<GraphSnapshotEntry> findSnapshotById(long snapshotId);

    /** 快照是否仍被某个发布指针作为活动目标。 */
    boolean isActivePublicationTarget(long snapshotId);

    /** 快照所属 run 是否仍处于活动构建状态。 */
    boolean hasActiveBuildForSnapshot(long snapshotId);

    /** 配对下按新近程度排列的快照 id，用于最少保留检查。 */
    List<Long> findRecentSnapshotIds(long kbId, int chunkIndexVersion, int limit);

    /** 退役第一步：置 DELETING 使新 pin 失败；返回 false 表示状态不允许。 */
    boolean markSnapshotDeleting(long snapshotId);

    Optional<Instant> findSnapshotRetiredAt(long snapshotId);

    /** 快照资源清单中尚未删除的行，按确定顺序返回供幂等重试。 */
    List<GraphResourceReferenceRecord> findPendingResourceReferences(long snapshotId);

    boolean markResourceDeleting(long resourceId);

    boolean markResourceDeleted(long resourceId);

    boolean markResourceFailed(long resourceId, String errorSummary);

    /** 快照来源清单中的去重资源身份（资源粒度），供全来源覆盖证明使用。 */
    List<com.kwiki.graph.GraphResourceId> findManifestResourceIds(long snapshotId);

    /** 日期主键幂等插入调度触发记录；false 表示当天已处理。 */
    boolean insertScheduleTrigger(LocalDate scheduleDate, String status, Long linkedBatchId);

    Optional<GraphScheduleTriggerRecord> findScheduleTrigger(LocalDate scheduleDate);

    /** 是否仍有未终态批次（先恢复并记录 SKIPPED_ACTIVE，不创建重复批次）。 */
    Optional<Long> findActiveBatchId();

    /** 管理端重试：可重试终态 run 回到 QUEUED。 */
    boolean requeueRun(long runId);

    /** 管理端取消：活动状态 run 进入 CANCELLED。 */
    boolean cancelRun(long runId);

    /** 写入图管理审计记录。 */
    void audit(String action, String idempotencyKey, String operator,
               Long batchId, Long runId, String resultState, String summary);

    /** 幂等重放：同 idempotency_key 且已完成的审计摘要。 */
    Optional<String> findCompletedAuditSummary(String idempotencyKey);
}
