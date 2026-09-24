package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 发布决策边界：默认手动发布，只有批次提交时持久化的 autoPublish 标志为真
 * 才自动发布。自动发布复用 {@link GraphSnapshotPublicationService} 的同一门禁
 * （校验清单、epoch 复核、按 (kbId, chunkIndexVersion) 的单次 CAS），只会推进
 * 任务固定 Chunk 版本下的图配对，不切换 Chunk 读别名，也不影响其他版本的配对。
 */
@Service
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphSnapshotAutoPublishService {

    private final GraphBuildRepository repository;
    private final GraphSnapshotPublicationService publication;

    public GraphSnapshotAutoPublishService(GraphBuildRepository repository,
                                           GraphSnapshotPublicationService publication) {
        this.repository = repository;
        this.publication = publication;
    }

    /** 管理员手动发布入口复用的同一门禁；显式调用不受 autoPublish 标志影响。 */
    public boolean publishManually(GraphSnapshot snapshot, GraphSnapshotState state,
                                   GraphValidationChecklist checklist, Long expectedSnapshotId) {
        return publication.publish(snapshot, state, checklist, expectedSnapshotId);
    }

    /**
     * 构建 READY 之后调用；批次未开启 autoPublish 时保持候选等待管理员，
     * 返回 false 且不触发任何发布事务。
     */
    public boolean publishIfAutoPublishEnabled(long runId, GraphSnapshot snapshot,
                                               GraphSnapshotState state,
                                               GraphValidationChecklist checklist,
                                               Long expectedSnapshotId) {
        if (!repository.findBatchAutoPublishForRun(runId)) {
            return false;
        }
        return publication.publish(snapshot, state, checklist, expectedSnapshotId);
    }
}
