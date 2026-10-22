package com.kwiki.eval;

import com.kwiki.rag.orchestration.RunObserver;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.QaRetrievalBudgets;
import com.kwiki.rag.retrieval.ScopeFilter;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 一次评测 run 的编排：提交（开始 / 继续）只做校验并入队（状态 QUEUED），由 {@link EvalWorker}
 * 认领后在本实例执行——逐题（有界并发）跑基线检索与完整工作流 → 分层计分（chunk 与 context 层只认
 * 来自该题来源文档的片段）→ 逐题写检查点（写入令牌校验，防接管后的僵尸实例）并按 run 缓冲上报
 * Langfuse（满 20 条或收尾时同步发送，成功即标记已上报）。开始执行（含继续、接管）先补报
 * 「有检查点但未上报」的题。单题失败只记错误继续；有未上报的题标记 reportPartial，下次继续再补。
 * 任一令牌校验写入失败说明归属已被其他实例接管：记 INFO 后取消在途题目静默退出，不写任何终态。
 */
@Service
public class EvalRunService {

    private static final Logger log = LoggerFactory.getLogger(EvalRunService.class);
    static final Duration CASE_TIMEOUT = Duration.ofSeconds(200);
    /** 心跳超过该时长未更新、且不在本实例执行的 RUNNING run 视为已中断（实例重启或失联）。 */
    static final Duration STALE_AFTER = Duration.ofMinutes(2);
    /** 集群名额占位时长，执行期间随心跳续期；实例宕机后到期自动释放。 */
    static final Duration SLOT_TTL = Duration.ofSeconds(60);
    /** 中断请求最多等待 runner 收尾（上报、写终态）的时长，超时则返回当时状态由前端继续轮询。 */
    static final Duration CANCEL_WAIT = Duration.ofSeconds(5);
    static final int HISTORY_LIMIT = 50;
    /** 上报批量：每 run 的缓冲达到该条数即同步发送一批。 */
    static final int REPORT_BATCH = 20;
    static final String INTERRUPTED = "interrupted";
    static final String CANCELLED = "cancelled";
    static final String SUBMITTER_NOT_AUTHORIZED = "submitter-not-authorized";
    static final String ACTION_CANCEL = "cancel";
    private static final long POLL_MILLIS = 200;
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    public record StartRequest(String dataset, Long kbId, String runName, String notes, boolean localOnly) {
    }

    public record RunStatus(String runId, String runName, String dataset, EvalRunState state, int total,
                            int completed, EvalSummary summary, String error, boolean localOnly,
                            boolean reportPartial, Instant createdAt, String ownerInstanceId,
                            Long queuePosition) {
    }

    /** 删除结果：langfuseDeleted 表示已调用 Langfuse 删除接口成功；失败时 langfuseError 为原因。 */
    public record DeleteResult(boolean langfuseDeleted, String langfuseError) {
    }

    /** 本实例上正在执行的 run；token 为认领时发放的写入令牌。 */
    private static final class Execution {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean ownershipLost = new AtomicBoolean();
        final CountDownLatch done = new CountDownLatch(1);
        final long token;
        volatile ExecutorService pool;
        volatile AutoCloseable runLock;

        Execution(long token) {
            this.token = token;
        }

        void cancel() {
            cancelled.set(true);
            ExecutorService current = pool;
            if (current != null) current.shutdownNow();
        }
    }

    private record Prepared(EvalRunStore.RunMeta meta, EvalDatasetSize size, List<EvalCase> cases,
                            Map<Long, Integer> pageDocIds) {
    }

    /** 按 run 缓冲的待上报条目：caseId 与重建好的 trace。 */
    private record Buffered(String caseId, EvalReportPort.TraceRecord trace) {
    }

    private final EvalDatasetLoader loader;
    private final BaselineRetrievalProbe baseline;
    private final WorkflowProbe workflow;
    private final AuthorizationScopeResolver scopes;
    private final EvalCorpusService corpus;
    private final EvalReportPort report;
    private final EvalRunStore store;
    private final EvalWorkQueue queue;
    private final EvalClusterSlots slots;
    private final EvalControlBus control;
    private final EvalRunLocks runLocks;
    private final QaRetrievalBudgets budgets;
    private final EvalInstanceId instance;
    private final int concurrency;
    private final int maxConcurrentRuns;
    private final RefusalDetector refusal;
    private final Clock clock;
    private volatile Duration heartbeatInterval = Duration.ofSeconds(5);
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("eval-heartbeat-", 0).daemon(true).factory());
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Buffered>> reportBuffers = new ConcurrentHashMap<>();
    private Runnable controlUnsubscribe;

    @Autowired
    public EvalRunService(EvalDatasetLoader loader, BaselineRetrievalProbe baseline, WorkflowProbe workflow,
                          AuthorizationScopeResolver scopes, EvalCorpusService corpus, EvalReportPort report,
                          EvalRunStore store, EvalWorkQueue queue, EvalClusterSlots slots, EvalControlBus control,
                          EvalRunLocks runLocks, QaRetrievalBudgets budgets, EvalInstanceId instance,
                          @Value("${kwiki.eval.concurrency:2}") int concurrency,
                          @Value("${kwiki.eval.max-concurrent-runs:2}") int maxConcurrentRuns) {
        this(loader, baseline, workflow, scopes, corpus, report, store, queue, slots, control, runLocks, budgets,
                instance, concurrency, maxConcurrentRuns, RefusalDetector.DEFAULT_PATTERNS, Clock.systemUTC());
    }

    EvalRunService(EvalDatasetLoader loader, BaselineRetrievalProbe baseline, WorkflowProbe workflow,
                   AuthorizationScopeResolver scopes, EvalCorpusService corpus, EvalReportPort report,
                   EvalRunStore store, EvalWorkQueue queue, EvalClusterSlots slots, EvalControlBus control,
                   EvalRunLocks runLocks, QaRetrievalBudgets budgets, EvalInstanceId instance,
                   int concurrency, int maxConcurrentRuns, List<String> refusalPatterns, Clock clock) {
        this.loader = loader;
        this.baseline = baseline;
        this.workflow = workflow;
        this.scopes = scopes;
        this.corpus = corpus;
        this.report = report;
        this.store = store;
        this.queue = queue;
        this.slots = slots;
        this.control = control;
        this.runLocks = runLocks;
        this.budgets = budgets;
        this.instance = instance;
        this.concurrency = Math.max(1, Math.min(concurrency, 8));
        this.maxConcurrentRuns = Math.max(1, maxConcurrentRuns);
        this.refusal = new RefusalDetector(refusalPatterns);
        this.clock = clock;
    }

    /** 测试用：缩短心跳间隔。 */
    void heartbeatInterval(Duration interval) {
        this.heartbeatInterval = interval;
    }

    @PostConstruct
    void subscribeControl() {
        controlUnsubscribe = control.subscribe((runId, action) -> {
            if (!ACTION_CANCEL.equals(action)) return;
            Execution execution = executions.get(runId);
            if (execution != null) execution.cancel();
        });
    }

    @PreDestroy
    void unsubscribeControl() {
        if (controlUnsubscribe != null) controlUnsubscribe.run();
    }

    /** 校验后入队，立即返回 QUEUED；没有空闲名额时排队，不再报 eval-busy。 */
    public RunStatus start(CurrentUser user, StartRequest request) {
        Prepared prepared = prepareNew(user, request);
        EvalRunStore.RunMeta meta = withSubmittedBy(prepared.meta(), user);
        store.create(meta);
        queue.enqueue(meta.runId());
        log.info("eval run queued runId={} runName={} submittedBy={}", meta.runId(), meta.runName(), meta.submittedBy());
        return status(meta.runId());
    }

    /** 继续已中断或失败的 run：CAS 置回 QUEUED 后入队，由空闲实例认领执行（可跨实例）。 */
    public RunStatus resume(CurrentUser user, String runId) {
        EvalRunStore.RunRecord record = record(runId);
        EvalRunState state = effectiveState(record);
        if (state != EvalRunState.CANCELLED && state != EvalRunState.FAILED) {
            throw new EvalException(409, "eval-run-not-resumable");
        }
        EvalRunStore.RunMeta meta = record.meta();
        if (!meta.localOnly() && !report.available()) throw new EvalException(503, "langfuse-not-configured");
        EvalDatasetSize size = EvalDatasetSize.parse(meta.dataset());
        if (EvalDatasetLoader.select(loader.load(), size).isEmpty()) {
            throw new EvalException(409, "eval-dataset-empty");
        }
        corpus.requireReady(user, meta.kbId());
        if (!store.markQueued(runId, String.valueOf(user.id()), clock.instant(), STALE_AFTER.toMillis())) {
            // 并发重复「继续」只有一个成功
            throw new EvalException(409, "eval-run-not-resumable");
        }
        queue.enqueue(runId);
        log.info("eval run requeued runId={} runName={} submittedBy={}", runId, meta.runName(), user.id());
        return status(runId);
    }

    /**
     * 中断：排队中直接取消；运行中写取消标志并广播控制通知（本实例在执行则立即取消在途题，
     * 等待收尾最多 {@link #CANCEL_WAIT}；通知丢失时执行实例的心跳轮询兜底）。
     */
    public RunStatus cancel(String runId) {
        EvalRunStore.RunRecord record = record(runId);
        EvalRunState state = effectiveState(record);
        if (state == EvalRunState.QUEUED) {
            store.cancelQueued(runId);
            return status(runId);
        }
        if (state != EvalRunState.RUNNING) throw new EvalException(409, "eval-run-not-running");
        store.requestCancel(runId);
        control.publish(runId, ACTION_CANCEL);
        Execution execution = executions.get(runId);
        if (execution != null) {
            execution.cancel();
            try {
                execution.done.await(CANCEL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return status(runId);
    }

    /** 删除已结束的 run；langfuse=true 时同时删除其在 Langfuse 上的 trace 与 dataset run（失败不回滚本地删除）。 */
    public DeleteResult delete(String runId, boolean langfuse) {
        EvalRunStore.RunRecord record = record(runId);
        EvalRunState state = effectiveState(record);
        if (state == EvalRunState.RUNNING) throw new EvalException(409, "eval-run-running");
        if (state == EvalRunState.QUEUED) {
            store.cancelQueued(runId);
            // 认领与删除并发：已被其他实例认领执行则拒绝
            if (effectiveState(record(runId)) == EvalRunState.RUNNING) {
                throw new EvalException(409, "eval-run-running");
            }
        }
        EvalRunStore.RunMeta meta = record.meta();
        List<String> traceIds = store.cases(runId).keySet().stream()
                .map(caseId -> traceId(meta.runName(), caseId)).sorted().toList();
        store.delete(runId);
        if (!langfuse || meta.localOnly() || !report.available()) return new DeleteResult(false, null);
        try {
            report.deleteRun(EvalDatasetSize.parse(meta.dataset()).datasetName(), meta.runName(), traceIds);
            return new DeleteResult(true, null);
        } catch (RuntimeException e) {
            log.warn("eval langfuse delete failed run={} reason={}", meta.runName(), e.getMessage());
            return new DeleteResult(false, e.getMessage());
        }
    }

    public RunStatus status(String runId) {
        return toStatus(record(runId));
    }

    /** 最近的 run，按创建时间倒序。 */
    public List<RunStatus> list() {
        return store.list(HISTORY_LIMIT).stream().map(this::toStatus).toList();
    }

    /** 完整报告（与原本地报告同结构）：runName、config、summary、按数据集顺序的逐题结果。 */
    public Map<String, Object> report(String runId) {
        EvalRunStore.RunRecord record = record(runId);
        List<CaseResult> results = ordered(store.cases(runId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runName", record.meta().runName());
        body.put("runId", runId);
        body.put("state", effectiveState(record));
        body.put("config", record.meta().config());
        body.put("summary", record.summary() != null ? record.summary() : EvalSummaries.summarize(results));
        body.put("cases", results);
        return body;
    }

    /** 同步执行（测试用）：入队、认领、执行一步到位，不经过队列与名额。 */
    RunStatus runNow(CurrentUser user, StartRequest request) {
        Prepared prepared = prepareNew(user, request);
        EvalRunStore.RunMeta meta = withSubmittedBy(prepared.meta(), user);
        store.create(meta);
        if (!claimForExecution(meta.runId(), user)) throw new IllegalStateException("claim failed: " + meta.runId());
        executeClaimed(meta.runId(), user);
        return status(meta.runId());
    }

    private EvalRunStore.RunRecord record(String runId) {
        return store.find(runId).orElseThrow(() -> new EvalException(404, "eval-run-not-found"));
    }

    /** 存储里是 RUNNING、但不在本实例执行且心跳过期的 run 视为已中断。 */
    private EvalRunState effectiveState(EvalRunStore.RunRecord record) {
        return stale(record) ? EvalRunState.CANCELLED : record.state();
    }

    /** 认领线程复用：RUNNING 且心跳过期（执行实例失联）。 */
    boolean isStale(EvalRunStore.RunRecord record) {
        return stale(record);
    }

    /** 测试观察：该 run 是否正在本实例执行。 */
    boolean hasExecution(String runId) {
        return executions.containsKey(runId);
    }

    private boolean stale(EvalRunStore.RunRecord record) {
        if (record.state() != EvalRunState.RUNNING || executions.containsKey(record.meta().runId())) return false;
        Instant beat = record.heartbeatAt() == null ? record.meta().createdAt() : record.heartbeatAt();
        return beat.plus(STALE_AFTER).isBefore(clock.instant());
    }

    private RunStatus toStatus(EvalRunStore.RunRecord record) {
        EvalRunStore.RunMeta meta = record.meta();
        boolean stale = stale(record);
        EvalRunState state = stale ? EvalRunState.CANCELLED : record.state();
        Long queuePosition = null;
        if (state == EvalRunState.QUEUED) {
            long position = store.queuePosition(meta.runId());
            if (position >= 0) queuePosition = position;
        }
        return new RunStatus(meta.runId(), meta.runName(), meta.dataset(), state, meta.total(),
                store.caseCount(meta.runId()), record.summary(), stale ? INTERRUPTED : record.error(),
                meta.localOnly(), record.reportPartial(), meta.createdAt(), record.ownerInstanceId(),
                queuePosition);
    }

    /** 按当前数据集顺序排列；数据集里已不存在的题排在最后。 */
    private List<CaseResult> ordered(Map<String, CaseResult> results) {
        Map<String, Integer> order = new HashMap<>();
        List<EvalCase> all = loader.load();
        for (int i = 0; i < all.size(); i++) order.put(all.get(i).id(), i);
        return results.values().stream()
                .sorted(Comparator.comparing((CaseResult result) ->
                        order.getOrDefault(result.evalCase().id(), Integer.MAX_VALUE))
                        .thenComparing(result -> result.evalCase().id()))
                .toList();
    }

    private Prepared prepareNew(CurrentUser user, StartRequest request) {
        EvalDatasetSize size;
        try {
            size = EvalDatasetSize.parse(request.dataset());
        } catch (IllegalArgumentException e) {
            throw new EvalException(400, "unknown-dataset");
        }
        if (request.kbId() == null) throw new EvalException(400, "kbId-required");
        if (!request.localOnly() && !report.available()) throw new EvalException(503, "langfuse-not-configured");
        List<EvalCase> cases = EvalDatasetLoader.select(loader.load(), size);
        if (cases.isEmpty()) throw new EvalException(409, "eval-dataset-empty");
        corpus.requireReady(user, request.kbId());
        Map<Long, Integer> pageDocIds = corpus.pageDocIds(user, request.kbId(), EvalCorpusService.DEFAULT_CORPUS);
        String runName = request.runName() == null || request.runName().isBlank()
                ? size.label() + "-" + RUN_TIME.format(LocalDateTime.now(clock.withZone(ZoneId.systemDefault())))
                : request.runName();
        Map<String, Object> config = configSnapshot(request, size);
        config.put("sourceDocPages", pageDocIds.size());
        EvalRunStore.RunMeta meta = new EvalRunStore.RunMeta(UUID.randomUUID().toString(), runName, size.label(),
                request.kbId(), request.notes() == null ? "" : request.notes(), request.localOnly(), cases.size(),
                config, clock.instant(), String.valueOf(user.id()));
        return new Prepared(meta, size, cases, pageDocIds);
    }

    private static EvalRunStore.RunMeta withSubmittedBy(EvalRunStore.RunMeta meta, CurrentUser user) {
        return new EvalRunStore.RunMeta(meta.runId(), meta.runName(), meta.dataset(), meta.kbId(), meta.notes(),
                meta.localOnly(), meta.total(), meta.config(), meta.createdAt(), String.valueOf(user.id()));
    }

    // ===== 认领与执行（EvalWorker 调用）=====

    /**
     * 认领归属：获取 run 锁后原子取得写入令牌并置 RUNNING。返回 false 表示认领未完成
     * （run 锁被其他实例短暂占用），任务不被确认、由挂起回收稍后重试；返回 true 后调用方
     * 应确认任务并调用 {@link #executeClaimed}。
     */
    public boolean claimForExecution(String runId, CurrentUser submitter) {
        EvalRunStore.RunRecord record = store.find(runId).orElse(null);
        if (record == null) return true;
        AutoCloseable lock = null;
        if (runLocks.isAvailable()) {
            lock = runLocks.tryAcquire(runId);
            if (lock == null) {
                log.info("eval run lock busy, retry via pending reclaim runId={} instance={}",
                        runId, instance.get());
                return false;
            }
        }
        long token = store.claimOwnership(runId, instance.get(), clock.instant());
        if (token < 0) {
            closeQuietly(lock);
            return true;
        }
        Execution execution = new Execution(token);
        execution.runLock = lock;
        executions.put(runId, execution);
        return true;
    }

    /** 执行已认领的 run：心跳（含名额续期）→ 补报 → 逐题 → 收尾。终态写入与检查点均经令牌校验。 */
    public void executeClaimed(String runId, CurrentUser submitter) {
        Execution execution = executions.get(runId);
        if (execution == null) return;
        EvalRunStore.RunRecord record = store.find(runId).orElse(null);
        if (record == null) {
            finishExecution(runId, execution);
            return;
        }
        EvalRunStore.RunMeta meta = record.meta();
        ScheduledFuture<?> heartbeat = null;
        try {
            heartbeat = heartbeats.scheduleAtFixedRate(() -> beat(runId, execution),
                    heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
            Prepared prepared = preparedFor(meta, submitter);
            ScopeFilter filter = ScopeFilter.from(scopes.resolve(submitter, Set.of(meta.kbId()), Set.of()));
            boolean reported = true;
            if (!meta.localOnly()) {
                reported = backfill(meta, prepared, execution.token);
            }
            Set<String> done = store.cases(runId).keySet();
            List<EvalCase> pending = prepared.cases().stream().filter(c -> !done.contains(c.id())).toList();
            evaluateAll(submitter, prepared, filter, pending, execution);
            if (execution.ownershipLost.get()) return; // 归属已被接管：静默退出，不写任何终态
            boolean cancelled = execution.cancelled.get();
            boolean partial = !reported || !flushBuffer(runId, meta, execution.token);
            Set<String> selected = prepared.cases().stream().map(EvalCase::id).collect(Collectors.toSet());
            List<CaseResult> results = ordered(store.cases(runId)).stream()
                    .filter(result -> selected.contains(result.evalCase().id())).toList();
            EvalSummary summary = EvalSummaries.summarize(results);
            EvalRunState state = cancelled ? EvalRunState.CANCELLED
                    : partial ? EvalRunState.REPORT_PARTIAL : EvalRunState.COMPLETED;
            if (!store.finish(runId, execution.token, state, summary, partial, cancelled ? CANCELLED : null)) {
                ownershipLost(runId, execution);
            }
        } catch (Exception e) {
            if (execution.ownershipLost.get()) return;
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (!meta.localOnly()) flushBuffer(runId, meta, execution.token);
            if (!store.finish(runId, execution.token, EvalRunState.FAILED, null, false, error)) {
                ownershipLost(runId, execution);
                return;
            }
            log.warn("eval run failed run={} reason={}", meta.runName(), error);
        } finally {
            if (heartbeat != null) heartbeat.cancel(false);
            finishExecution(runId, execution);
        }
    }

    private void finishExecution(String runId, Execution execution) {
        executions.remove(runId);
        closeQuietly(execution.runLock);
        execution.runLock = null;
        reportBuffers.remove(runId);
        execution.done.countDown();
    }

    /** 执行期数据集与来源文档按提交人重算（检索作用域不沿用提交时的权限）。 */
    private Prepared preparedFor(EvalRunStore.RunMeta meta, CurrentUser submitter) {
        EvalDatasetSize size = EvalDatasetSize.parse(meta.dataset());
        List<EvalCase> cases = EvalDatasetLoader.select(loader.load(), size);
        Map<Long, Integer> pageDocIds = corpus.pageDocIds(submitter, meta.kbId(), EvalCorpusService.DEFAULT_CORPUS);
        return new Prepared(meta, size, cases, pageDocIds);
    }

    /** 心跳：令牌校验刷新存活时间、续期集群名额，并拾取取消标志（控制通知丢失时的兜底）。 */
    private void beat(String runId, Execution execution) {
        try {
            if (!store.heartbeat(runId, execution.token, clock.instant())
                    || !slots.renew(instance.get(), SLOT_TTL)) {
                ownershipLost(runId, execution);
                return;
            }
            if (store.find(runId).map(EvalRunStore.RunRecord::cancelRequested).orElse(true)) execution.cancel();
        } catch (RuntimeException e) {
            log.warn("eval heartbeat failed run={} reason={}", runId, e.getMessage());
        }
    }

    private void ownershipLost(String runId, Execution execution) {
        if (execution.ownershipLost.compareAndSet(false, true)) {
            log.info("eval run ownership lost runId={} instance={}", runId, instance.get());
        }
        execution.cancel();
    }

    /** 开始执行（含继续、接管）时补报：有检查点但未上报的题从检查点重建 trace 上报并标记。 */
    private boolean backfill(EvalRunStore.RunMeta meta, Prepared prepared, long token) {
        String runId = meta.runId();
        Set<String> reported = store.reportedCaseIds(runId);
        List<Buffered> pending = store.cases(runId).entrySet().stream()
                .filter(entry -> !reported.contains(entry.getKey()))
                .map(entry -> new Buffered(entry.getKey(),
                        trace(meta, prepared.size(), entry.getValue().evalCase(), entry.getValue())))
                .toList();
        if (pending.isEmpty()) return true;
        log.info("eval report backfill run={} cases={}", meta.runName(), pending.size());
        for (int from = 0; from < pending.size(); from += REPORT_BATCH) {
            if (!uploadBatch(runId, meta, token, pending.subList(from,
                    Math.min(from + REPORT_BATCH, pending.size())))) return false;
        }
        return true;
    }

    /**
     * 工作线程只计算结果；认领线程统一经令牌校验写检查点并按 run 攒批上报，因此取消
     * （中断工作线程）不会波及其他题的上报。取消后完成的题返回 null 被丢弃；
     * 取消前已完成但尚未收取的结果仍会保存。
     */
    private void evaluateAll(CurrentUser user, Prepared prepared, ScopeFilter filter, List<EvalCase> cases,
                             Execution execution) throws InterruptedException {
        EvalRunStore.RunMeta meta = prepared.meta();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency,
                Thread.ofPlatform().name("eval-case-", 0).daemon(true).factory());
        execution.pool = pool;
        if (execution.cancelled.get()) pool.shutdownNow();
        CompletionService<CaseResult> completion = new ExecutorCompletionService<>(pool);
        int submitted = 0;
        try {
            for (EvalCase evalCase : cases) {
                try {
                    completion.submit(() -> {
                        CaseResult result = evaluate(user, meta.kbId(), filter, prepared.pageDocIds(), evalCase);
                        return execution.cancelled.get() ? null : result;
                    });
                    submitted++;
                } catch (RejectedExecutionException e) {
                    break; // 已取消
                }
            }
            for (int received = 0; received < submitted && !execution.cancelled.get(); ) {
                Future<CaseResult> future = completion.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (future == null) continue;
                received++;
                accept(prepared, future, execution);
            }
            if (execution.cancelled.get()) {
                Future<CaseResult> future;
                while ((future = completion.poll()) != null) accept(prepared, future, execution);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void accept(Prepared prepared, Future<CaseResult> future, Execution execution) {
        CaseResult result;
        try {
            result = future.get();
        } catch (ExecutionException | CancellationException | InterruptedException e) {
            return;
        }
        if (result == null) return;
        EvalRunStore.RunMeta meta = prepared.meta();
        String runId = meta.runId();
        if (!store.saveCase(runId, execution.token, result)) {
            ownershipLost(runId, execution);
            return;
        }
        if (meta.localOnly() || !report.available()) return;
        Deque<Buffered> buffer = reportBuffers.computeIfAbsent(runId, id -> new ArrayDeque<>());
        boolean flushNow;
        synchronized (buffer) {
            buffer.add(new Buffered(result.evalCase().id(),
                    trace(meta, prepared.size(), result.evalCase(), result)));
            flushNow = buffer.size() >= REPORT_BATCH;
        }
        if (flushNow) flushBuffer(runId, meta, execution.token);
    }

    /** 上报本 run 缓冲中的全部剩余条目；返回是否全部成功（失败者仍可凭检查点在下次继续时补报）。 */
    private boolean flushBuffer(String runId, EvalRunStore.RunMeta meta, long token) {
        Deque<Buffered> buffer = reportBuffers.get(runId);
        if (buffer == null) return true;
        while (true) {
            List<Buffered> batch = new ArrayList<>();
            synchronized (buffer) {
                for (int i = 0; i < REPORT_BATCH && !buffer.isEmpty(); i++) batch.add(buffer.poll());
            }
            if (batch.isEmpty()) return true;
            if (!uploadBatch(runId, meta, token, batch)) return false;
        }
    }

    /** 同步上报一批并经令牌校验标记已上报；上报失败的条目直接丢弃（凭检查点补报），标记失败视为归属丢失。 */
    private boolean uploadBatch(String runId, EvalRunStore.RunMeta meta, long token, List<Buffered> batch) {
        List<String> uploaded;
        try {
            uploaded = report.upload(batch.stream().map(Buffered::trace).toList());
        } catch (RuntimeException e) {
            log.warn("eval report upload failed run={} traces={} reason={}",
                    meta.runName(), batch.size(), e.getMessage());
            return false;
        }
        Set<String> ok = new HashSet<>(uploaded);
        boolean allUploaded = ok.size() == batch.size();
        for (Buffered item : batch) {
            if (!ok.contains(item.trace().traceId())) continue;
            if (!store.markReported(runId, token, item.caseId())) return false;
        }
        return allUploaded;
    }

    CaseResult evaluate(CurrentUser user, long kbId, ScopeFilter filter, Map<Long, Integer> pageDocIds,
                        EvalCase evalCase) {
        long started = System.nanoTime();
        try {
            // 评测线程上的基线检索查询向量也计入评测，用户为触发评测的管理员
            BaselineRetrievalProbe.Result base;
            WorkflowProbe.Result run;
            try (var usage = com.kwiki.usage.ModelUsageContext.open(
                    com.kwiki.usage.UsageSource.EVAL, user.id())) {
                base = baseline.probe(evalCase.query(), filter);
                run = workflow.run(user, kbId, evalCase.query(), CASE_TIMEOUT);
            }
            SourceDocFilter source = new SourceDocFilter(pageDocIds, evalCase);
            Map<EvalLayer, List<String>> segments = new EnumMap<>(EvalLayer.class);
            Map<EvalLayer, List<String>> keys = new EnumMap<>(EvalLayer.class);
            Map<EvalLayer, Integer> filtered = new EnumMap<>(EvalLayer.class);
            Set<String> poolFiltered = new HashSet<>();
            base.byLayer().forEach((layer, hits) -> {
                Map<Boolean, List<ChunkHit>> split = hits.stream().collect(Collectors.partitioningBy(
                        hit -> source.admits(hit.resourceType(), hit.resourceId())));
                segments.put(layer, split.get(true).stream().map(ChunkHit::content).toList());
                keys.put(layer, hits.stream().map(ChunkHit::chunkKey).toList());
                filtered.put(layer, split.get(false).size());
                if (layer == EvalLayer.BM25_50 || layer == EvalLayer.VECTOR_50) {
                    split.get(false).forEach(hit -> poolFiltered.add(hit.chunkKey()));
                }
            });
            filtered.put(EvalLayer.POOL_50, poolFiltered.size());
            List<String> context = run.contextSegments().stream()
                    .filter(segment -> source.admits(segment.resourceType(), segment.resourceId()))
                    .map(RunObserver.ContextSegment::text).toList();
            segments.put(EvalLayer.CONTEXT, context);
            filtered.put(EvalLayer.CONTEXT, run.contextSegments().size() - context.size());
            segments.put(EvalLayer.ANSWER, List.of(run.answer() == null ? "" : run.answer()));
            CaseScore score = evalCase.negative() ? null : LayerScorer.score(evalCase.keywords(), segments);
            Boolean refused = evalCase.negative() ? refusal.refused(run.outcome(), run.answer()) : null;
            return new CaseResult(evalCase, score, refused, run.outcome(), run.answer(), run.rewrites(), keys,
                    filtered, run.errorCode(), elapsedMs(started));
        } catch (RuntimeException e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            return new CaseResult(evalCase, null, null, null, "", List.of(), Map.of(), Map.of(),
                    reason.length() > 300 ? reason.substring(0, 300) : reason, elapsedMs(started));
        }
    }

    static String traceId(String runName, String caseId) {
        return UUID.nameUUIDFromBytes((runName + ":" + caseId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private EvalReportPort.TraceRecord trace(EvalRunStore.RunMeta meta, EvalDatasetSize size, EvalCase evalCase,
                                             CaseResult result) {
        String traceId = traceId(meta.runName(), evalCase.id());
        Instant now = Instant.now();
        List<EvalReportPort.SpanRecord> spans = new ArrayList<>();
        List<EvalReportPort.ScoreRecord> scores = new ArrayList<>();
        if (result.error()) {
            scores.add(new EvalReportPort.ScoreRecord("error", 1, result.errorCode()));
        } else if (evalCase.negative()) {
            scores.add(new EvalReportPort.ScoreRecord("refusal",
                    Boolean.TRUE.equals(result.refusedCorrectly()) ? 1 : 0,
                    refusal.reason(result.outcome(), result.answer()).orElse("no-refusal outcome=" + result.outcome())));
        } else {
            for (EvalLayer layer : EvalLayer.values()) {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("matched", aliases(evalCase, result.score().matched(layer)));
                output.put("chunkKeys", result.chunkKeys().getOrDefault(layer, List.of()));
                if (layer != EvalLayer.ANSWER) {
                    output.put("nonSourceFiltered", result.nonSourceFiltered().getOrDefault(layer, 0));
                }
                spans.add(new EvalReportPort.SpanRecord(
                        UUID.nameUUIDFromBytes((traceId + ":" + layer.metric()).getBytes(StandardCharsets.UTF_8))
                                .toString(),
                        layer.metric(), now, now, Map.of("query", evalCase.query()), output));
                scores.add(new EvalReportPort.ScoreRecord("coverage@" + layer.metric(),
                        result.score().coverage(layer), null));
                scores.add(new EvalReportPort.ScoreRecord("fullHit@" + layer.metric(),
                        result.score().fullHit(layer) ? 1 : 0, null));
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("category", evalCase.category());
        metadata.put("domain", evalCase.domain());
        metadata.put("source", evalCase.source());
        metadata.put("tier", evalCase.tier());
        metadata.put("rewrites", result.rewrites());
        if (result.score() != null) {
            Map<String, String> losses = new LinkedHashMap<>();
            result.score().losses().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    losses.put(evalCase.keywords().get(entry.getKey()).get(0), entry.getValue().name()));
            metadata.put("losses", losses);
            metadata.put("contextGains", result.score().contextGains().size());
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("outcome", result.outcome());
        output.put("answer", result.answer());
        return new EvalReportPort.TraceRecord(traceId, "eval:" + evalCase.id(),
                Map.of("query", evalCase.query()), output, metadata, List.of("rag-eval", size.label()),
                spans, scores, EvalDatasetSyncService.itemId(size, evalCase), meta.runName(), meta.config());
    }

    private static List<String> aliases(EvalCase evalCase, Set<Integer> groups) {
        return groups.stream().sorted(Comparator.naturalOrder())
                .map(group -> evalCase.keywords().get(group).get(0)).toList();
    }

    private Map<String, Object> configSnapshot(StartRequest request, EvalDatasetSize size) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dataset", size.label());
        config.put("kbId", request.kbId());
        config.put("notes", request.notes() == null ? "" : request.notes());
        config.put("baseBranchTopK", budgets.baseBranchTopK);
        config.put("baseFinalTopK", budgets.baseFinalTopK);
        config.put("expandedBranchTopK", budgets.expandedBranchTopK);
        config.put("expandedFinalTopK", budgets.expandedFinalTopK);
        config.put("baseParentLimit", budgets.baseParentLimit);
        config.put("expandedParentLimit", budgets.expandedParentLimit);
        config.put("concurrency", concurrency);
        config.put("maxConcurrentRuns", maxConcurrentRuns);
        config.put("localOnly", request.localOnly());
        return config;
    }

    private static void closeQuietly(AutoCloseable handle) {
        if (handle == null) return;
        try {
            handle.close();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception ignored) {
            // AutoCloseable 签名要求；解锁路径自身不会抛受检异常
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
