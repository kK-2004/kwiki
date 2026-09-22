package com.kwiki.graph.persistence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * VALIDATING 阶段的封存边界：硬失败只留下可诊断报告，快照保持 BUILDING/候选，
 * 不能进入 READY；报告通过时单次条件更新封存并持久化报告。
 */
@Service
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
@ConditionalOnBean(GraphBuildRepository.class)
public class GraphSnapshotSealingService {

    private final GraphBuildRepository repository;

    public GraphSnapshotSealingService(GraphBuildRepository repository) {
        this.repository = repository;
    }

    /** @return 报告通过且 BUILDING → READY 条件更新成功时为 true。 */
    public boolean seal(long snapshotId, GraphSnapshotValidationReport report) {
        if (report == null) {
            throw new IllegalArgumentException("校验报告不能为空");
        }
        String json = report.toJson();
        if (!report.valid()) {
            repository.persistValidationReport(snapshotId, json);
            return false;
        }
        return repository.sealSnapshotReady(snapshotId, json);
    }
}
