package com.kwiki.eval;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 评测 run 的持久化与执行协调：元信息、状态、逐题检查点、排队索引与写入令牌。
 * 启用 Redis 时跨重启、跨实例可见，否则退化为进程内存储（重启即丢失）。
 * 状态机：QUEUED ─认领─▶ RUNNING ─▶ COMPLETED / REPORT_PARTIAL / FAILED，
 * QUEUED/RUNNING 可中断为 CANCELLED；CANCELLED / FAILED 可重新提交回 QUEUED。
 *
 * <p>写入令牌：认领（{@link #claimOwnership}）时发放，此后逐题检查点、心跳、终态、
 * 已上报标记一律经令牌校验写入——run 被其他实例接管后旧实例的写入被拒绝，
 * 防止僵尸实例覆盖新实例的数据。
 */
public interface EvalRunStore {

    /** run 的不可变元信息；config 为启动时的配置快照，继续时沿用；submittedBy 为提交执行的管理员 id。 */
    record RunMeta(String runId, String runName, String dataset, long kbId, String notes, boolean localOnly,
                   int total, Map<String, Object> config, Instant createdAt, String submittedBy) {

        public RunMeta {
            submittedBy = submittedBy == null ? "" : submittedBy;
        }
    }

    /** 某一时刻的完整 run 记录（不含逐题结果）。 */
    record RunRecord(RunMeta meta, EvalRunState state, EvalSummary summary, String error, boolean reportPartial,
                     Instant heartbeatAt, boolean cancelRequested, String ownerInstanceId, Instant queuedAt) {
    }

    /** 新建 run：写元信息、状态置 QUEUED、记录 queuedAt 与提交人、加入历史索引与排队索引。 */
    void create(RunMeta meta);

    Optional<RunRecord> find(String runId);

    /** 按创建时间倒序列出最近 limit 个 run。 */
    List<RunRecord> list(int limit);

    /**
     * 提交（开始 / 继续）CAS：CANCELLED / FAILED / 心跳过期超过 staleAfterMillis 的 RUNNING → QUEUED，
     * 记录提交人与 queuedAt；并发重复提交只有一个成功。
     */
    boolean markQueued(String runId, String submittedBy, Instant now, long staleAfterMillis);

    /** 排队中取消：QUEUED → CANCELLED 并移出排队索引；非 QUEUED 返回 false。 */
    boolean cancelQueued(String runId);

    /** QUEUED run 之前仍在排队的 run 数（按 queuedAt）；不在排队返回 -1。 */
    long queuePosition(String runId);

    /**
     * 认领归属：令牌自增、置 RUNNING（清除错误与取消标志）、记录执行实例并刷新心跳、移出排队索引；
     * 记录不存在（已删除）返回 -1。
     */
    long claimOwnership(String runId, String instanceId, Instant now);

    /** 逐题检查点，以 caseId 幂等覆盖；令牌校验失败或记录已删除返回 false 且不写入。 */
    boolean saveCase(String runId, long ownerToken, CaseResult result);

    /** caseId → 结果。 */
    Map<String, CaseResult> cases(String runId);

    int caseCount(String runId);

    /** 已确认上报到 Langfuse 的 caseId 集合。 */
    Set<String> reportedCaseIds(String runId);

    /** 标记逐题已上报（令牌校验）。 */
    boolean markReported(String runId, long ownerToken, String caseId);

    /** 写入终态（令牌校验）；summary / error 可为 null（为 null 时清除对应字段）。 */
    boolean finish(String runId, long ownerToken, EvalRunState state, EvalSummary summary, boolean reportPartial,
                   String error);

    /** 心跳（令牌校验）：刷新存活时间。 */
    boolean heartbeat(String runId, long ownerToken, Instant now);

    /** 系统级失败（无令牌，认领期校验用，如提交人已不是管理员）；记录不存在时忽略。 */
    void markFailed(String runId, String error);

    /** 系统级中断收尾（无令牌）：认领时发现「已请求取消且执行实例失联」的 RUNNING 置为 CANCELLED；仅 RUNNING 生效。 */
    void markCancelled(String runId);

    /** 跨实例取消标志：持有该 run 的实例在下次心跳或控制通知时执行取消。 */
    void requestCancel(String runId);

    void delete(String runId);
}
