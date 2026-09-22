package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 发布唯一提交点：先重核 epoch，再按 `(kbId, chunkIndexVersion)` CAS。 */
@Service
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
@ConditionalOnBean(GraphBuildRepository.class)
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
}
