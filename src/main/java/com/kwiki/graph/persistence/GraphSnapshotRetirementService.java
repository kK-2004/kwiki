package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSnapshot;
import com.kwiki.graph.config.GraphProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 历史快照回滚与退役清理。回滚只把配对指针切回完整且 Chunk 版本兼容的历史
 * 快照，来源/epoch 复核照常由在线门禁执行，epoch 过期时摘要保持禁用；
 * 引用已物理删除资源的快照不得作为回滚目标。清理先置 DELETING 使新 pin 失败，
 * 再等待宽限期与读取租约结束，按快照资源清单幂等删除；部分失败保持 DELETING
 * 及剩余资源，可再次重试。「最少保留当前与上一版本」的豁免不适用于引用了
 * 已物理删除来源的快照——它们在宽限期后即可清理。
 */
@Service
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphSnapshotRetirementService {

    private final GraphBuildRepository repository;
    private final GraphSourceEpochService epochs;
    private final com.kwiki.graph.GraphSourceInventoryPort inventory;
    private final int retentionMinSnapshots;
    private final java.time.Duration retirementGrace;

    public GraphSnapshotRetirementService(GraphBuildRepository repository,
                                          GraphSourceEpochService epochs,
                                          com.kwiki.graph.GraphSourceInventoryPort inventory,
                                          GraphProperties properties) {
        this.repository = repository;
        this.epochs = epochs;
        this.inventory = inventory;
        this.retentionMinSnapshots = properties.retentionMinSnapshots();
        this.retirementGrace = properties.retirementGrace();
    }

    /** 回滚到历史快照；expectedSnapshotId 为空表示当前无配对。 */
    public boolean rollback(long kbId, int chunkIndexVersion, long targetSnapshotId,
                            Long expectedSnapshotId) {
        GraphSnapshotEntry target = repository.findSnapshotById(targetSnapshotId)
                .orElseThrow(() -> new IllegalStateException("回滚目标快照不存在"));
        List<com.kwiki.graph.GraphResourceId> manifest =
                repository.findManifestResourceIds(targetSnapshotId);
        java.util.Set<com.kwiki.graph.GraphResourceId> existing = inventory.existingResources(manifest);
        GraphSnapshotRollbackGuard.requireRollbackable(target, chunkIndexVersion,
                manifest, existing::contains);
        long[] current = epochs.currentForUpdate(kbId);
        return repository.compareAndSetPublication(kbId, chunkIndexVersion,
                expectedSnapshotId, targetSnapshotId, current[0], current[1], Instant.now());
    }

    /** 退役第一步：拒绝发布/构建/保留范围内的目标，其余置 DELETING。 */
    public boolean retireForCleanup(long snapshotId) {
        GraphSnapshotEntry entry = repository.findSnapshotById(snapshotId)
                .orElseThrow(() -> new IllegalStateException("快照不存在"));
        GraphSnapshot snapshot = entry.snapshot();
        if (repository.isActivePublicationTarget(snapshotId)
                || repository.hasActiveBuildForSnapshot(snapshotId)) {
            throw new IllegalStateException("快照仍被发布或构建引用，不能退役");
        }
        List<Long> retained = repository.findRecentSnapshotIds(
                snapshot.kbId(), Math.toIntExact(snapshot.chunkIndexVersion()),
                retentionMinSnapshots);
        if (retained.contains(snapshotId) && !referencesPurgedSource(snapshotId)) {
            throw new IllegalStateException("快照在最少保留范围内（当前/上一版本），不能退役");
        }
        GraphSnapshotDeletionGuard.requireDeletable(entry.state(), false, false,
                repository.hasActiveReadLeases(snapshotId));
        return repository.markSnapshotDeleting(snapshotId);
    }

    /** 快照来源清单是否引用了业务库中已物理删除的资源。 */
    private boolean referencesPurgedSource(long snapshotId) {
        List<com.kwiki.graph.GraphResourceId> manifest = repository.findManifestResourceIds(snapshotId);
        java.util.Set<com.kwiki.graph.GraphResourceId> existing = inventory.existingResources(manifest);
        return manifest.stream().anyMatch(resource -> !existing.contains(resource));
    }

    /** 是否允许执行物理删除：宽限期已过且无未到期读取租约。 */
    public boolean cleanupEligible(long snapshotId) {
        Optional<Instant> retiredAt = repository.findSnapshotRetiredAt(snapshotId);
        if (retiredAt.isEmpty()) {
            return false;
        }
        if (Instant.now().isBefore(retiredAt.get().plus(retirementGrace))) {
            return false;
        }
        return !repository.hasActiveReadLeases(snapshotId);
    }

    /** 按资源清单幂等删除；部分失败返回 remaining>0，快照保持 DELETING 可重试。 */
    public CleanupResult cleanupResources(long snapshotId,
                                          GraphSnapshotResourceCleanupPort cleaner) {
        if (!cleanupEligible(snapshotId) || !retiredForCleanup(snapshotId)) {
            return new CleanupResult(0,
                    repository.findPendingResourceReferences(snapshotId).size(), false);
        }
        // 资源清单在快照创建时没有登记：清理前按快照行补齐（幂等）
        repository.ensureSnapshotResources(snapshotId);
        List<GraphResourceReferenceRecord> pending =
                repository.findPendingResourceReferences(snapshotId);
        int deleted = 0;
        for (GraphResourceReferenceRecord resource : pending) {
            repository.markResourceDeleting(resource.id());
            boolean ok;
            String error = "resource-delete-failed";
            try {
                ok = cleaner != null && cleaner.delete(resource.resourceKind(),
                        resource.resourceIdentity());
            } catch (RuntimeException failure) {
                ok = false;
                if (failure.getMessage() != null) {
                    error = failure.getMessage().length() > 900
                            ? failure.getMessage().substring(0, 900) : failure.getMessage();
                }
            }
            if (ok) {
                repository.markResourceDeleted(resource.id());
                deleted++;
            } else {
                repository.markResourceFailed(resource.id(), error);
            }
        }
        int remaining = repository.findPendingResourceReferences(snapshotId).size();
        return new CleanupResult(deleted, remaining, remaining == 0);
    }

    /**
     * 只清理已进入 DELETING、且不再被发布指针引用的快照：退役第一步已拒绝活动发布目标，
     * 这里再核对一次，防止对仍在线的快照删除外部数据。
     */
    private boolean retiredForCleanup(long snapshotId) {
        return repository.findSnapshotById(snapshotId)
                .map(entry -> entry.state() == GraphSnapshotState.DELETING)
                .orElse(false)
                && !repository.isActivePublicationTarget(snapshotId);
    }

    /** 一次清理调用的结果；complete 表示资源清单已全部删除。 */
    public record CleanupResult(int deleted, int remaining, boolean complete) {
    }
}
