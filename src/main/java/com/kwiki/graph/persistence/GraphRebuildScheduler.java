package com.kwiki.graph.persistence;

import com.kwiki.graph.config.GraphRuntimeSettings;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import com.kwiki.indexing.version.SearchIndexVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.kwiki.graph.persistence.GraphRebuildRequestRepository.GraphRebuildRequestRecord;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 去抖自动重建：内容/权限变化由 {@link GraphSourceChangeRecorder} 标记待重建，
 * 本调度器每分钟在分布式锁 {@code graph:rebuild} 下扫描。一个库同时满足
 * 以下条件才提交单库构建：已有发布快照（当前选中的 Chunk 版本，从未建图的库
 * 不自动建）、静默期已到或最长延迟已到或硬删除、无活动构建、无未完成索引任务。
 * 提交幂等键 {@code GRAPH_REBUILD:{kbId}:{contentEpoch}:{securityEpoch}}，
 * autoPublish 取本轮配置快照中的 rebuild.auto-publish（依赖第 0 组热更新）。
 * 发布快照 epoch 覆盖 dirty epoch 后清除记录；STALE/FAILED/CANCELLED 记录
 * 结局并按指数退避（上限 max-delay）后重试。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphRebuildScheduler {

    private static final Logger log = LoggerFactory.getLogger(GraphRebuildScheduler.class);
    static final String REQUESTED_BY = "graph-rebuild";
    private static final String LOCK = "graph:rebuild";
    /** 仍在构建或等待人工处理的子任务状态，与 hasActiveBuild 的口径一致。 */
    private static final Set<String> BUILDING_STATES = Set.of("QUEUED", "EXTRACTING", "PROJECTING",
            "CLUSTERING", "SUMMARIZING", "INDEXING", "VALIDATING", "WAITING_FOR_CHUNKS",
            "UNSUPPORTED", "NEEDS_ATTENTION");
    /** 需要退避后重提的结束状态。 */
    private static final Set<String> FAILED_STATES = Set.of("STALE", "FAILED", "CANCELLED",
            "PARTIAL_FAILED");

    private final GraphRebuildRequestRepository requests;
    private final GraphBuildTargets targets;
    private final GraphBuildBatchService batches;
    private final GraphRuntimeSettings settings;
    private final KwikiDistributedLocks locks;
    private final GraphBuildMetrics metrics;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public GraphRebuildScheduler(GraphRebuildRequestRepository requests,
                                 GraphBuildTargets targets,
                                 GraphBuildBatchService batches,
                                 GraphRuntimeSettings settings,
                                 KwikiDistributedLocks locks,
                                 GraphBuildMetrics metrics) {
        this(requests, targets, batches, settings, locks, metrics, Clock.systemUTC());
    }

    GraphRebuildScheduler(GraphRebuildRequestRepository requests,
                          GraphBuildTargets targets,
                          GraphBuildBatchService batches,
                          GraphRuntimeSettings settings,
                          KwikiDistributedLocks locks,
                          GraphBuildMetrics metrics,
                          Clock clock) {
        this.requests = requests;
        this.targets = targets;
        this.batches = batches;
        this.settings = settings;
        this.locks = locks;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *")
    public void tick() {
        runOnce();
    }

    /** 一轮调度；返回本轮提交的构建数。锁不可用时跳过（不无锁执行）。 */
    public int runOnce() {
        if (!locks.isAvailable()) {
            return 0;
        }
        // 每轮开始取一次配置快照：同一轮里所有库用同一份开关、静默期、最长延迟与 autoPublish
        GraphRuntimeSettings.Snapshot config = settings.snapshot();
        if (!config.rebuildEnabled()) {
            return 0;
        }
        SearchIndexVersion selected = targets.selectedVersion().orElse(null);
        if (selected == null) {
            return 0;
        }
        Integer submitted = locks.tryRunReturning(LOCK, Duration.ofSeconds(10), () -> {
            int count = 0;
            for (GraphRebuildRequestRecord request : requests.findAll()) {
                try {
                    if (process(request, config, selected)) {
                        count++;
                    }
                } catch (RuntimeException failure) {
                    log.warn("graph rebuild round failed for kb {}: {}",
                            request.kbId(), failure.getMessage());
                }
            }
            return count;
        });
        return submitted == null ? 0 : submitted;
    }

    private boolean process(GraphRebuildRequestRecord record,
                            GraphRuntimeSettings.Snapshot config,
                            SearchIndexVersion selected) {
        Instant now = clock.instant();
        // 知识库已归档或已物理删除：不再需要图谱，清除记录（恢复时会重新标记）
        if (!requests.knowledgeBaseActive(record.kbId())) {
            requests.clear(record.kbId());
            log.info("graph rebuild request cleared for inactive kb {}", record.kbId());
            return false;
        }
        // 该库在当前选中 Chunk 版本下没有已发布快照：从未建图（或版本刚切换）
        // 的库不自动建，避免默默放大模型成本
        long[] published = requests.publishedEpochs(record.kbId(), selected.getVersionNumber());
        if (published == null) {
            return false;
        }
        // 发布快照的 epoch 已覆盖 dirty epoch：重建完成，清除记录（门禁恢复 FULL）
        if (published[0] >= record.dirtyContentEpoch()
                && published[1] >= record.dirtySecurityEpoch()) {
            requests.clear(record.kbId());
            return false;
        }
        // 已提交批次：构建中则等待；失败类终态记录结局并退避；其余结束态
        // （已发布但其后又有编辑、候选已过期、子任务缺失）解除等待后按普通记录继续
        if (record.lastSubmittedBatchId() != null
                && !settleSubmission(record, config, now)) {
            return false;
        }
        if (record.nextAttemptAt() != null && now.isBefore(record.nextAttemptAt())) {
            return false; // 退避中
        }
        if (requests.hasActiveBuild(record.kbId())) {
            return false; // 该库没有活动中的图构建子任务
        }
        if (requests.hasPendingIndexingJobs(record.kbId())) {
            return false; // ES 尚未反映最新内容，先等索引任务完成
        }
        boolean quietElapsed = !now.isBefore(record.lastDirtyAt().plus(config.rebuildQuietPeriod()));
        boolean maxDelayElapsed = !now.isBefore(record.firstDirtyAt().plus(config.rebuildMaxDelay()));
        if (!quietElapsed && !maxDelayElapsed && !record.hardDelete()) {
            return false;
        }
        try {
            GraphBuildBatchRequest batch = targets.request(rebuildKey(record),
                    GraphBuildTargets.SCOPE_KNOWLEDGE_BASE, List.of(record.kbId()),
                    selected.getVersionNumber(), null, config.rebuildAutoPublish(),
                    REQUESTED_BY, null);
            GraphBuildSubmission submission = batches.submit(batch);
            requests.recordSubmitted(record.kbId(), submission.batchId());
            metrics.recordRebuildSubmitted();
            log.info("graph rebuild submitted kb={} batch={} key={} autoPublish={} replayed={}",
                    record.kbId(), submission.batchId(), rebuildKey(record),
                    config.rebuildAutoPublish(), submission.replayed());
            return !submission.replayed();
        } catch (RuntimeException failure) {
            metrics.recordRebuildFailed();
            requests.recordOutcome(record.kbId(), "FAILED",
                    now.plus(backoff(record.attemptCount() + 1, config)));
            log.warn("graph rebuild submit failed kb={}: {}", record.kbId(), failure.getMessage());
            return false;
        }
    }

    /**
     * 处理已提交的批次；返回 true 表示已解除等待、本轮可继续判断是否重提。
     * <ul>
     *   <li>仍在构建（含 WAITING_FOR_CHUNKS / UNSUPPORTED / NEEDS_ATTENTION 等待人工处理）：等待；</li>
     *   <li>STALE / FAILED / CANCELLED / PARTIAL_FAILED：记录结局并退避；</li>
     *   <li>READY 且候选已覆盖当前 dirty epoch：等待管理员发布（rebuild.auto-publish 关闭时）；</li>
     *   <li>其余（PUBLISHED、READY 但其后又有变化、子任务缺失）：解除等待。</li>
     * </ul>
     * 走到这里说明发布快照还没有覆盖 dirty epoch，否则前面已经清除了记录。
     */
    private boolean settleSubmission(GraphRebuildRequestRecord record,
                                     GraphRuntimeSettings.Snapshot config, Instant now) {
        GraphRebuildRequestRepository.SubmittedRun run =
                requests.submittedRun(record.lastSubmittedBatchId(), record.kbId());
        String state = run == null ? null : run.state();
        if (state != null && BUILDING_STATES.contains(state)) {
            return false;
        }
        if (state != null && FAILED_STATES.contains(state)) {
            if ("STALE".equals(state)) {
                metrics.recordRebuildStale();
            } else {
                metrics.recordRebuildFailed();
            }
            String outcome = "PARTIAL_FAILED".equals(state) ? "FAILED" : state;
            Instant nextAttempt = now.plus(backoff(record.attemptCount(), config));
            requests.recordOutcome(record.kbId(), outcome, nextAttempt);
            log.info("graph rebuild {} for kb {}, retry after {}",
                    state.toLowerCase(), record.kbId(), nextAttempt);
            return false;
        }
        if ("READY".equals(state)
                && run.contentEpoch() >= record.dirtyContentEpoch()
                && run.securityEpoch() >= record.dirtySecurityEpoch()) {
            return false; // 候选仍是最新的，等待管理员发布
        }
        requests.releaseSubmission(record.kbId());
        log.info("graph rebuild batch {} for kb {} ended as {} without covering dirty epochs, "
                        + "scheduling again", record.lastSubmittedBatchId(), record.kbId(), state);
        return true;
    }

    /**
     * 提交幂等键：dirty epoch 标识本轮变化；重试带 #retryN 后缀生成新批次，
     * 避免 STALE 批次被幂等回放后永远无法重建。同一 epoch 的首次提交
     * （含并发实例）仍由唯一键去重。
     */
    static String rebuildKey(GraphRebuildRequestRecord record) {
        String base = "GRAPH_REBUILD:" + record.kbId() + ":" + record.dirtyContentEpoch()
                + ":" + record.dirtySecurityEpoch();
        return record.attemptCount() <= 0 ? base : base + "#retry" + record.attemptCount();
    }

    /** 指数退避：quiet-period 起步翻倍，上限 max-delay。 */
    static Duration backoff(int attemptCount, GraphRuntimeSettings.Snapshot config) {
        int shift = Math.min(Math.max(attemptCount, 1) - 1, 16);
        long seconds = config.rebuildQuietPeriod().getSeconds() << shift;
        return Duration.ofSeconds(Math.min(seconds, config.rebuildMaxDelay().getSeconds()));
    }
}
