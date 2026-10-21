package com.kwiki.eval;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 评测 run 的持久化：元信息、状态与逐题检查点。启用 Redis 时跨重启、跨实例可见，
 * 否则退化为进程内存储（重启即丢失）。所有写操作按字段进行，不做整体覆盖。
 */
public interface EvalRunStore {

    /** run 的不可变元信息；config 为启动时的配置快照，继续时沿用。 */
    record RunMeta(String runId, String runName, String dataset, long kbId, String notes, boolean localOnly,
                   int total, Map<String, Object> config, Instant createdAt) {
    }

    /** 某一时刻的完整 run 记录（不含逐题结果）。 */
    record RunRecord(RunMeta meta, EvalRunState state, EvalSummary summary, String error, boolean reportPartial,
                     Instant heartbeatAt, boolean cancelRequested) {
    }

    /** 新建 run：写元信息、状态置 RUNNING、加入历史索引。 */
    void create(RunMeta meta);

    Optional<RunRecord> find(String runId);

    /** 按创建时间倒序列出最近 limit 个 run。 */
    List<RunRecord> list(int limit);

    /** 逐题检查点，以 caseId 幂等覆盖。 */
    void saveCase(String runId, CaseResult result);

    /** caseId → 结果。 */
    Map<String, CaseResult> cases(String runId);

    int caseCount(String runId);

    /** 置为运行中（继续时），清除错误、取消标志与上报不完整标记，并刷新心跳。 */
    void markRunning(String runId, Instant now);

    /** 写入终态；summary 可为 null。 */
    void finish(String runId, EvalRunState state, EvalSummary summary, boolean reportPartial, String error);

    void heartbeat(String runId, Instant now);

    /** 跨实例取消标志：持有该 run 的实例在下次心跳时执行取消。 */
    void requestCancel(String runId);

    void delete(String runId);
}
