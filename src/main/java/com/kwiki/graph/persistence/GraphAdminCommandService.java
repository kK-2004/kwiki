package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 图管理命令边界：幂等键重放、审计与既有领域服务的编排。发布/回滚/清理
 * 复用与自动发布相同的门禁；长操作返回 batchId/runId，不回显凭据。
 */
@Service
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
@ConditionalOnBean(GraphBuildRepository.class)
public class GraphAdminCommandService {

    private final GraphBuildRepository repository;
    private final GraphSnapshotAutoPublishService autoPublish;
    private final GraphSnapshotRetirementService retirement;
    private final GraphBuildSlotService slot;

    public GraphAdminCommandService(GraphBuildRepository repository,
                                    GraphSnapshotAutoPublishService autoPublish,
                                    GraphSnapshotRetirementService retirement,
                                    GraphBuildSlotService slot) {
        this.repository = repository;
        this.autoPublish = autoPublish;
        this.retirement = retirement;
        this.slot = slot;
    }

    /** 幂等执行：同键已完成命令直接重放原摘要，不产生重复副作用。 */
    public Map<String, Object> execute(String idempotencyKey, String action,
                                       String operator, Long batchId, Long runId,
                                       Supplier<Map<String, Object>> command) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key 不能为空");
        }
        String prior = repository.findCompletedAuditSummary(idempotencyKey).orElse(null);
        if (prior != null) {
            return Map.of("replayed", true, "action", action, "summary", prior);
        }
        repository.audit(action, idempotencyKey, operator, batchId, runId,
                "ACCEPTED", null);
        Map<String, Object> response;
        try {
            response = command.get();
        } catch (RuntimeException failure) {
            repository.audit(action, idempotencyKey, operator, batchId, runId,
                    "FAILED", bounded(failure.getMessage()));
            throw failure;
        }
        repository.audit(action, idempotencyKey, operator, batchId, runId,
                "COMPLETED", String.valueOf(response.get("summary") == null
                        ? response : response.get("summary")));
        return response;
    }

    /** 管理员显式发布：与自动发布共用同一门禁，不切换 Chunk 别名。 */
    public Map<String, Object> publish(long kbId, int chunkIndexVersion, long snapshotId,
                                       Long expectedSnapshotId) {
        GraphSnapshotEntry entry = repository.findSnapshotById(snapshotId)
                .orElseThrow(() -> new IllegalArgumentException("快照不存在"));
        GraphValidationChecklist checklist = checklistOf(snapshotId);
        boolean published = autoPublish.publishManually(entry.snapshot(), entry.state(),
                checklist, expectedSnapshotId);
        return Map.of("published", published, "kbId", kbId,
                "chunkIndexVersion", chunkIndexVersion, "snapshotId", snapshotId,
                "summary", "publish:" + published);
    }

    /** 回滚到完整且 Chunk 版本兼容的历史快照。 */
    public Map<String, Object> rollback(long kbId, int chunkIndexVersion, long targetSnapshotId,
                                        Long expectedSnapshotId) {
        boolean switched = retirement.rollback(kbId, chunkIndexVersion, targetSnapshotId,
                expectedSnapshotId);
        return Map.of("rolledBack", switched, "kbId", kbId,
                "chunkIndexVersion", chunkIndexVersion, "snapshotId", targetSnapshotId,
                "summary", "rollback:" + switched);
    }

    /** 退役快照进入清理（DELETING + 资源清单幂等删除）。 */
    public Map<String, Object> retire(long snapshotId) {
        boolean retiring = retirement.retireForCleanup(snapshotId);
        return Map.of("retired", retiring, "snapshotId", snapshotId,
                "summary", "retire:" + retiring);
    }

    public Map<String, Object> cleanup(long snapshotId) {
        var result = retirement.cleanupResources(snapshotId, null);
        return Map.<String, Object>of("deleted", result.deleted(),
                "remaining", result.remaining(), "complete", result.complete(),
                "snapshotId", snapshotId,
                "summary", "cleanup:" + result.deleted() + "/" + result.remaining());
    }

    /** 在全局构建槽位内重试子任务；槽位忙时返回 BUSY。 */
    public Map<String, Object> retry(long runId) {
        return slot.withBuildSlot(() -> {
            boolean requeued = repository.requeueRun(runId);
            return Map.<String, Object>of("runId", runId, "requeued", requeued,
                    "summary", "retry:" + requeued);
        }).orElse(Map.<String, Object>of("runId", runId, "busy", true,
                "summary", "retry:busy"));
    }

    public Map<String, Object> cancel(long runId) {
        boolean cancelled = repository.cancelRun(runId);
        return Map.of("runId", runId, "cancelled", cancelled,
                "summary", "cancel:" + cancelled);
    }

    /** 从持久化校验报告恢复发布门禁清单；缺失报告视为未通过。 */
    private GraphValidationChecklist checklistOf(long snapshotId) {
        String json = repository.findSnapshotValidationJson(snapshotId).orElse(null);
        GraphSnapshotValidationReport report = GraphSnapshotValidationReport.fromJson(json);
        return report == null
                ? new GraphValidationChecklist(false, false, false, false, false,
                        false, false)
                : report.toChecklist();
    }

    private static String bounded(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 900 ? message.substring(0, 900) : message;
    }
}
