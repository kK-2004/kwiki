package com.kwiki.graph.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 删除图构建批次。批次与子任务行保留为墓碑：graphVersion 与 COMMUNITY 版本都按 MAX+1
 * 分配，物理删除行会让下一次构建复用旧版本号、写进尚未清理的残留数据。
 *
 * <p>流程：
 * <ol>
 *   <li>事务内锁住批次与子任务，校验：子任务都已结束、快照未被发布指针引用、没有未到期的读取租约；
 *       然后把批次标记已删除、快照置 DELETING（之后无法再被发布、回滚或固定读取）、
 *       解除自动重建对该批次的等待；</li>
 *   <li>事务提交后清理外部数据：每个子任务的 ArcadeDB {@code (kbId, graphVersion)} 图数据
 *       与社区 ES 物理索引；</li>
 *   <li>把清理结果记在批次上。失败时批次仍在列表中显示「清理失败」，再次删除即重试清理。</li>
 * </ol>
 */
@Service
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphBatchDeletionService {

    private static final Logger log = LoggerFactory.getLogger(GraphBatchDeletionService.class);

    /** 仍在构建或等待人工处理的子任务状态：这些子任务需要先取消。 */
    static final Set<String> ACTIVE_RUN_STATES = Set.of("QUEUED", "EXTRACTING", "PROJECTING",
            "CLUSTERING", "SUMMARIZING", "INDEXING", "VALIDATING", "WAITING_FOR_CHUNKS",
            "UNSUPPORTED", "NEEDS_ATTENTION");

    /** 删除结果；cleanupState 为 DONE 表示外部数据已清理干净。 */
    public record Result(long batchId, boolean alreadyDeleted, String cleanupState,
                         int cleanedRuns, List<String> failures) {
    }

    /** 需要清理外部数据的子任务。 */
    record RunTarget(long runId, long kbId, long graphVersion, String communityPhysicalIndex) {
    }

    private final JdbcOperations jdbc;
    private final TransactionTemplate transactions;
    /** 与快照退役清理共用的外部数据清理。 */
    private final GraphArtifactCleaner artifacts;

    @org.springframework.beans.factory.annotation.Autowired
    public GraphBatchDeletionService(ObjectProvider<JdbcOperations> jdbc,
                                     ObjectProvider<TransactionTemplate> transactions,
                                     GraphArtifactCleaner artifacts) {
        this(jdbc.getIfAvailable(), transactions.getIfAvailable(), artifacts);
    }

    GraphBatchDeletionService(JdbcOperations jdbc, TransactionTemplate transactions,
                              GraphArtifactCleaner artifacts) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.artifacts = artifacts;
    }

    public Result delete(long batchId, String operator) {
        Marked marked = inTransaction(() -> markDeleted(batchId, operator));
        if (marked.alreadyClean()) {
            return new Result(batchId, true, "DONE", 0, List.of());
        }
        List<String> failures = new ArrayList<>();
        int cleaned = 0;
        for (RunTarget target : marked.targets()) {
            if (cleanRun(target, failures)) {
                cleaned++;
            }
        }
        String state = failures.isEmpty() ? "DONE" : "FAILED";
        String summary = failures.isEmpty() ? null : bounded(String.join("；", failures));
        jdbc.update("UPDATE graph_build_batch SET cleanup_state = ?, cleanup_summary = ? WHERE id = ?",
                state, summary, batchId);
        if (failures.isEmpty()) {
            jdbc.update("UPDATE community_index_version SET state = 'RETIRED' WHERE batch_id = ?", batchId);
            log.info("graph batch {} deleted by {}: {} runs cleaned", batchId, operator, cleaned);
        } else {
            log.warn("graph batch {} deleted by {} but cleanup incomplete: {}", batchId, operator, summary);
        }
        return new Result(batchId, marked.alreadyDeleted(), state, cleaned, List.copyOf(failures));
    }

    private record Marked(boolean alreadyDeleted, boolean alreadyClean, List<RunTarget> targets) {
    }

    private Marked markDeleted(long batchId, String operator) {
        List<Map<String, Object>> batch = jdbc.queryForList(
                "SELECT deleted_at, cleanup_state FROM graph_build_batch WHERE id = ? FOR UPDATE", batchId);
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("批次不存在：" + batchId);
        }
        boolean alreadyDeleted = batch.get(0).get("deleted_at") != null;
        if (alreadyDeleted && "DONE".equals(batch.get(0).get("cleanup_state"))) {
            return new Marked(true, true, List.of());
        }
        List<Map<String, Object>> runs = jdbc.queryForList(
                "SELECT id, kb_id, graph_version, community_physical_index, state "
                        + "FROM graph_build_run WHERE batch_id = ? ORDER BY id FOR UPDATE", batchId);
        List<RunTarget> targets = new ArrayList<>();
        for (Map<String, Object> run : runs) {
            targets.add(new RunTarget(number(run.get("id")), number(run.get("kb_id")),
                    number(run.get("graph_version")), (String) run.get("community_physical_index")));
        }
        if (alreadyDeleted) {
            // 上次清理未完成：元数据已标记，直接重试外部清理
            jdbc.update("UPDATE graph_build_batch SET cleanup_state = 'PENDING' WHERE id = ?", batchId);
            return new Marked(true, false, targets);
        }
        for (Map<String, Object> run : runs) {
            String state = String.valueOf(run.get("state"));
            if (ACTIVE_RUN_STATES.contains(state)) {
                throw new IllegalStateException("子任务 #" + run.get("id") + " 仍在进行或等待处理（"
                        + state + "），请先取消再删除批次");
            }
        }
        List<Long> snapshotIds = jdbc.queryForList(
                "SELECT s.id FROM graph_snapshot s JOIN graph_build_run r ON r.id = s.run_id "
                        + "WHERE r.batch_id = ? ORDER BY s.id FOR UPDATE", Long.class, batchId);
        if (!snapshotIds.isEmpty()) {
            String in = snapshotIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            List<Long> published = jdbc.queryForList(
                    "SELECT active_snapshot_id FROM graph_publication WHERE active_snapshot_id IN (" + in + ") "
                            + "UNION SELECT expected_snapshot_id FROM graph_publication "
                            + "WHERE expected_snapshot_id IN (" + in + ")",
                    Long.class);
            if (!published.isEmpty()) {
                throw new IllegalStateException("快照 #" + published.get(0)
                        + " 正被发布配对使用，请先发布或回滚到其他快照再删除批次");
            }
            Boolean leased = jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM graph_snapshot_read_lease WHERE snapshot_id IN (" + in + ") "
                            + "AND expires_at > CURRENT_TIMESTAMP(6))", Boolean.class);
            if (Boolean.TRUE.equals(leased)) {
                throw new IllegalStateException("该批次的快照仍有进行中的读取，请稍后再删除");
            }
            jdbc.update("UPDATE graph_snapshot SET state = 'DELETING', "
                    + "retired_at = COALESCE(retired_at, CURRENT_TIMESTAMP(6)) WHERE id IN (" + in + ")");
        }
        jdbc.update("UPDATE graph_build_batch SET deleted_at = CURRENT_TIMESTAMP(6), deleted_by = ?, "
                + "cleanup_state = 'PENDING', cleanup_summary = NULL WHERE id = ?", operator, batchId);
        jdbc.update("UPDATE community_index_version SET state = 'DELETING' WHERE batch_id = ?", batchId);
        // 自动重建若在等这个批次，直接解除等待，下一轮按普通记录重新判断
        jdbc.update("UPDATE graph_rebuild_request SET last_submitted_batch_id = NULL, "
                + "next_attempt_at = NULL WHERE last_submitted_batch_id = ?", batchId);
        return new Marked(false, false, targets);
    }

    /** 清理一个子任务的外部数据；失败写入 failures，返回是否全部成功。 */
    private boolean cleanRun(RunTarget target, List<String> failures) {
        boolean ok = true;
        try {
            artifacts.deleteGraphVersion(target.kbId(), target.graphVersion());
        } catch (RuntimeException failure) {
            ok = false;
            failures.add("子任务 #" + target.runId() + " 图数据：" + failure.getMessage());
        }
        String index = target.communityPhysicalIndex();
        try {
            artifacts.deleteCommunityIndex(index);
        } catch (RuntimeException failure) {
            ok = false;
            failures.add("子任务 #" + target.runId() + " 社区索引 " + index + "：" + failure.getMessage());
        }
        return ok;
    }

    private <T> T inTransaction(Supplier<T> body) {
        return transactions == null ? body.get() : transactions.execute(status -> body.get());
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static String bounded(String message) {
        return message.length() > 900 ? message.substring(0, 900) : message;
    }
}
