package com.kwiki.eval;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 一次评测 run 的编排：校验 → 语料就绪检查 → 单实例互斥 → 逐题（有界并发）跑基线检索与完整工作流 →
 * 分层计分（chunk 与 context 层只认来自该题来源文档的片段）→ 逐题写检查点并上报 Langfuse → 汇总。
 * 单题失败只记错误继续；上报失败标记 reportPartial。run 状态与逐题结果保存在 {@link EvalRunStore}，
 * 支持中断（在途题立即取消、不计入结果）、继续（只跑没有检查点的题，沿用 runName）与删除。
 */
@Service
public class EvalRunService {

    private static final Logger log = LoggerFactory.getLogger(EvalRunService.class);
    static final Duration CASE_TIMEOUT = Duration.ofSeconds(200);
    /** 心跳超过该时长未更新、且不在本实例执行的 RUNNING run 视为已中断（实例重启或失联）。 */
    static final Duration STALE_AFTER = Duration.ofMinutes(2);
    /** 中断请求最多等待 runner 收尾（flush、写终态）的时长，超时则返回当时状态由前端继续轮询。 */
    static final Duration CANCEL_WAIT = Duration.ofSeconds(5);
    static final int HISTORY_LIMIT = 50;
    static final String INTERRUPTED = "interrupted";
    static final String CANCELLED = "cancelled";
    private static final long POLL_MILLIS = 200;
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    public record StartRequest(String dataset, Long kbId, String runName, String notes, boolean localOnly) {
    }

    public record RunStatus(String runId, String runName, String dataset, EvalRunState state, int total,
                            int completed, EvalSummary summary, String error, boolean localOnly,
                            boolean reportPartial, Instant createdAt) {
    }

    /** 删除结果：langfuseDeleted 表示已调用 Langfuse 删除接口成功；失败时 langfuseError 为原因。 */
    public record DeleteResult(boolean langfuseDeleted, String langfuseError) {
    }

    /** 本实例上正在执行的 run。 */
    private static final class Execution {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CountDownLatch done = new CountDownLatch(1);
        volatile ExecutorService pool;

        void cancel() {
            cancelled.set(true);
            ExecutorService current = pool;
            if (current != null) current.shutdownNow();
        }
    }

    private record Prepared(EvalRunStore.RunMeta meta, EvalDatasetSize size, List<EvalCase> cases,
                            Map<Long, Integer> pageDocIds) {
    }

    private final EvalDatasetLoader loader;
    private final BaselineRetrievalProbe baseline;
    private final WorkflowProbe workflow;
    private final AuthorizationScopeResolver scopes;
    private final EvalCorpusService corpus;
    private final EvalReportPort report;
    private final EvalRunStore store;
    private final KwikiDistributedLocks locks;
    private final QaRetrievalBudgets budgets;
    private final int concurrency;
    private final RefusalDetector refusal;
    private final Clock clock;
    private volatile Duration heartbeatInterval = Duration.ofSeconds(5);
    private final ExecutorService runner = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("eval-run-", 0).daemon(true).factory());
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("eval-heartbeat-", 0).daemon(true).factory());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();

    @Autowired
    public EvalRunService(EvalDatasetLoader loader, BaselineRetrievalProbe baseline, WorkflowProbe workflow,
                          AuthorizationScopeResolver scopes, EvalCorpusService corpus, EvalReportPort report,
                          EvalRunStore store, KwikiDistributedLocks locks, QaRetrievalBudgets budgets,
                          @Value("${kwiki.eval.concurrency:2}") int concurrency) {
        this(loader, baseline, workflow, scopes, corpus, report, store, locks, budgets, concurrency,
                RefusalDetector.DEFAULT_PATTERNS, Clock.systemUTC());
    }

    EvalRunService(EvalDatasetLoader loader, BaselineRetrievalProbe baseline, WorkflowProbe workflow,
                   AuthorizationScopeResolver scopes, EvalCorpusService corpus, EvalReportPort report,
                   EvalRunStore store, KwikiDistributedLocks locks, QaRetrievalBudgets budgets,
                   int concurrency, List<String> refusalPatterns, Clock clock) {
        this.loader = loader;
        this.baseline = baseline;
        this.workflow = workflow;
        this.scopes = scopes;
        this.corpus = corpus;
        this.report = report;
        this.store = store;
        this.locks = locks;
        this.budgets = budgets;
        this.concurrency = Math.max(1, Math.min(concurrency, 8));
        this.refusal = new RefusalDetector(refusalPatterns);
        this.clock = clock;
    }

    /** 测试用：缩短心跳间隔。 */
    void heartbeatInterval(Duration interval) {
        this.heartbeatInterval = interval;
    }

    /** 校验后异步启动；同一时刻只允许一个 run。 */
    public RunStatus start(CurrentUser user, StartRequest request) {
        return launch(user, prepareNew(user, request), false);
    }

    /** 继续已中断或失败的 run：只跑没有检查点的题，沿用 runId / runName / 配置快照。 */
    public RunStatus resume(CurrentUser user, String runId) {
        EvalRunStore.RunRecord record = record(runId);
        EvalRunState state = effectiveState(record);
        if (state != EvalRunState.CANCELLED && state != EvalRunState.FAILED) {
            throw new EvalException(409, "eval-run-not-resumable");
        }
        EvalRunStore.RunMeta meta = record.meta();
        if (!meta.localOnly() && !report.available()) throw new EvalException(503, "langfuse-not-configured");
        EvalDatasetSize size = EvalDatasetSize.parse(meta.dataset());
        List<EvalCase> cases = EvalDatasetLoader.select(loader.load(), size);
        if (cases.isEmpty()) throw new EvalException(409, "eval-dataset-empty");
        corpus.requireReady(user, meta.kbId());
        Map<Long, Integer> pageDocIds = corpus.pageDocIds(user, meta.kbId(), EvalCorpusService.DEFAULT_CORPUS);
        return launch(user, new Prepared(meta, size, cases, pageDocIds), true);
    }

    /**
     * 中断：本实例持有则立即取消在途题并等待 runner 收尾（最多 {@link #CANCEL_WAIT}）；
     * 否则写取消标志，由持有实例在下次心跳时执行。
     */
    public RunStatus cancel(String runId) {
        EvalRunStore.RunRecord record = record(runId);
        if (effectiveState(record) != EvalRunState.RUNNING) throw new EvalException(409, "eval-run-not-running");
        Execution execution = executions.get(runId);
        if (execution == null) {
            store.requestCancel(runId);
            return status(runId);
        }
        execution.cancel();
        try {
            execution.done.await(CANCEL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return status(runId);
    }

    /** 删除已结束的 run；langfuse=true 时同时删除其在 Langfuse 上的 trace 与 dataset run（失败不回滚本地删除）。 */
    public DeleteResult delete(String runId, boolean langfuse) {
        EvalRunStore.RunRecord record = record(runId);
        if (effectiveState(record) == EvalRunState.RUNNING) throw new EvalException(409, "eval-run-running");
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

    /** 同步执行（测试用），不经过互斥标志。 */
    RunStatus runNow(CurrentUser user, StartRequest request) {
        Prepared prepared = prepareNew(user, request);
        String runId = prepared.meta().runId();
        store.create(prepared.meta());
        Execution execution = new Execution();
        executions.put(runId, execution);
        try {
            execute(user, prepared, execution);
        } finally {
            executions.remove(runId);
            execution.done.countDown();
        }
        return status(runId);
    }

    private EvalRunStore.RunRecord record(String runId) {
        return store.find(runId).orElseThrow(() -> new EvalException(404, "eval-run-not-found"));
    }

    /** 存储里是 RUNNING、但不在本实例执行且心跳过期的 run 视为已中断。 */
    private EvalRunState effectiveState(EvalRunStore.RunRecord record) {
        return stale(record) ? EvalRunState.CANCELLED : record.state();
    }

    private boolean stale(EvalRunStore.RunRecord record) {
        if (record.state() != EvalRunState.RUNNING || executions.containsKey(record.meta().runId())) return false;
        Instant beat = record.heartbeatAt() == null ? record.meta().createdAt() : record.heartbeatAt();
        return beat.plus(STALE_AFTER).isBefore(clock.instant());
    }

    private RunStatus toStatus(EvalRunStore.RunRecord record) {
        EvalRunStore.RunMeta meta = record.meta();
        boolean stale = stale(record);
        return new RunStatus(meta.runId(), meta.runName(), meta.dataset(),
                stale ? EvalRunState.CANCELLED : record.state(), meta.total(), store.caseCount(meta.runId()),
                record.summary(), stale ? INTERRUPTED : record.error(), meta.localOnly(), record.reportPartial(),
                meta.createdAt());
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

    private RunStatus launch(CurrentUser user, Prepared prepared, boolean resume) {
        if (!busy.compareAndSet(false, true)) throw new EvalException(409, "eval-busy");
        String runId = prepared.meta().runId();
        Execution execution = new Execution();
        try {
            if (resume) store.markRunning(runId, clock.instant());
            else store.create(prepared.meta());
            executions.put(runId, execution);
            runner.submit(() -> {
                try {
                    execute(user, prepared, execution);
                } finally {
                    executions.remove(runId);
                    busy.set(false);
                    execution.done.countDown();
                }
            });
        } catch (RuntimeException e) {
            executions.remove(runId);
            busy.set(false);
            throw e;
        }
        return status(runId);
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
                config, clock.instant());
        return new Prepared(meta, size, cases, pageDocIds);
    }

    private void execute(CurrentUser user, Prepared prepared, Execution execution) {
        EvalRunStore.RunMeta meta = prepared.meta();
        String runId = meta.runId();
        AutoCloseable lock = null;
        ScheduledFuture<?> heartbeat = null;
        try {
            if (locks.isAvailable()) {
                lock = locks.acquire("eval-run", Duration.ZERO);
                if (lock == null) {
                    store.finish(runId, EvalRunState.FAILED, null, false, "eval-busy");
                    return;
                }
            }
            long interval = heartbeatInterval.toMillis();
            heartbeat = heartbeats.scheduleAtFixedRate(() -> beat(runId, execution), interval, interval,
                    TimeUnit.MILLISECONDS);
            ScopeFilter filter = ScopeFilter.from(scopes.resolve(user, Set.of(meta.kbId()), Set.of()));
            Set<String> done = store.cases(runId).keySet();
            List<EvalCase> pending = prepared.cases().stream().filter(c -> !done.contains(c.id())).toList();
            evaluateAll(user, prepared, filter, pending, execution);
            boolean cancelled = execution.cancelled.get();
            boolean partial = false;
            if (!meta.localOnly()) {
                try {
                    report.flush();
                } catch (RuntimeException e) {
                    partial = true;
                    log.warn("eval report upload incomplete run={} reason={}", meta.runName(), e.getMessage());
                }
            }
            Set<String> selected = prepared.cases().stream().map(EvalCase::id).collect(Collectors.toSet());
            List<CaseResult> results = ordered(store.cases(runId)).stream()
                    .filter(result -> selected.contains(result.evalCase().id())).toList();
            EvalSummary summary = EvalSummaries.summarize(results);
            EvalRunState state = cancelled ? EvalRunState.CANCELLED
                    : partial ? EvalRunState.REPORT_PARTIAL : EvalRunState.COMPLETED;
            store.finish(runId, state, summary, partial, cancelled ? CANCELLED : null);
        } catch (Exception e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (!meta.localOnly()) {
                try {
                    report.flush();
                } catch (RuntimeException ignored) {
                    // run 已失败，上报残留只尽力而为
                }
            }
            store.finish(runId, EvalRunState.FAILED, null, false, error);
            log.warn("eval run failed run={} reason={}", meta.runName(), error);
        } finally {
            if (heartbeat != null) heartbeat.cancel(false);
            if (lock != null) {
                try {
                    lock.close();
                } catch (Exception ignored) {
                    // 锁租期到期后 ownership 检查会阻止误解锁
                }
            }
        }
    }

    /** 心跳：刷新存活时间，并拾取其他实例写入的取消标志。 */
    private void beat(String runId, Execution execution) {
        try {
            store.heartbeat(runId, clock.instant());
            if (store.find(runId).map(EvalRunStore.RunRecord::cancelRequested).orElse(false)) execution.cancel();
        } catch (RuntimeException e) {
            log.warn("eval heartbeat failed run={} reason={}", runId, e.getMessage());
        }
    }

    /**
     * 工作线程只计算结果；runner 线程统一写检查点并上报，因此取消（中断工作线程）不会波及其他题的上报。
     * 取消后完成的题返回 null 被丢弃；取消前已完成但尚未收取的结果仍会保存。
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
        store.saveCase(meta.runId(), result);
        if (!meta.localOnly()) report.recordTrace(trace(meta, prepared.size(), result.evalCase(), result));
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
        config.put("localOnly", request.localOnly());
        return config;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
