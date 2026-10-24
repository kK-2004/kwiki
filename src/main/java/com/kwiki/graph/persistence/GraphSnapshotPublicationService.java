package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 发布唯一提交点：先重核 epoch，再按 `(kbId, chunkIndexVersion)` CAS。 */
@Service
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphSnapshotPublicationService {

    private final GraphBuildRepository repository;
    private final GraphSourceEpochService epochs;

    public GraphSnapshotPublicationService(GraphBuildRepository repository,
                                           GraphSourceEpochService epochs) {
        this.repository = repository;
        this.epochs = epochs;
    }

    @Transactional
    public boolean publish(GraphSnapshot snapshot, GraphSnapshotState state,
                           GraphValidationChecklist checklist, Long expectedSnapshotId) {
        checklist.requireValid();
        long[] current = epochs.currentForUpdate(snapshot.kbId());
        GraphSnapshotPublicationGuard.requirePublishable(snapshot, state,
                current[0], current[1], Math.toIntExact(snapshot.chunkIndexVersion()));
        return repository.compareAndSetPublication(snapshot.kbId(),
                Math.toIntExact(snapshot.chunkIndexVersion()), expectedSnapshotId,
                snapshot.snapshotId(), current[0], current[1], Instant.now());
    }

    /**
     * 自动发布：替换配对当前的活动快照（首次发布时为空）。期望值在持有 epoch
     * 行锁后读取——所有发布与回滚都先锁同一行，读到的指针在 CAS 前不会被改动。
     */
    @Transactional
    public boolean publishReplacingActive(GraphSnapshot snapshot, GraphSnapshotState state,
                                          GraphValidationChecklist checklist) {
        checklist.requireValid();
        long[] current = epochs.currentForUpdate(snapshot.kbId());
        int chunkIndexVersion = Math.toIntExact(snapshot.chunkIndexVersion());
        GraphSnapshotPublicationGuard.requirePublishable(snapshot, state,
                current[0], current[1], chunkIndexVersion);
        Long expectedSnapshotId = repository.findPublication(snapshot.kbId(), chunkIndexVersion)
                .map(GraphPublicationRecord::activeSnapshotId)
                .orElse(null);
        return repository.compareAndSetPublication(snapshot.kbId(), chunkIndexVersion,
                expectedSnapshotId, snapshot.snapshotId(), current[0], current[1], Instant.now());
    }
}
