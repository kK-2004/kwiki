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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 一次评测 run 的编排：校验 → 语料就绪检查 → 单实例互斥 → 逐题（有界并发）跑基线检索与完整工作流 →
 * 分层计分（chunk 与 context 层只认来自该题来源文档的片段）→ 上报 Langfuse → 写本地报告。单题失败只记错误继续；上报失败标记 REPORT_PARTIAL。
 * run 状态只在内存中，应用重启后进行中的 run 视为中断。
 */
@Service
public class EvalRunService {

    private static final Logger log = LoggerFactory.getLogger(EvalRunService.class);
    static final Duration CASE_TIMEOUT = Duration.ofSeconds(200);
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    public record StartRequest(String dataset, Long kbId, String runName, String notes, boolean localOnly) {
    }

    public enum State { RUNNING, COMPLETED, REPORT_PARTIAL, FAILED }

    public record RunStatus(String runId, String runName, String dataset, State state, int total, int completed,
                            EvalSummary summary, String reportPath, String error) {
    }

    private static final class RunState {
        final String runId = UUID.randomUUID().toString();
        final String runName;
        final EvalDatasetSize size;
        final int total;
        final AtomicInteger completed = new AtomicInteger();
        volatile State state = State.RUNNING;
        volatile EvalSummary summary;
        volatile String reportPath;
        volatile String error;

        RunState(String runName, EvalDatasetSize size, int total) {
            this.runName = runName;
            this.size = size;
            this.total = total;
        }

        RunStatus status() {
            return new RunStatus(runId, runName, size.label(), state, total, completed.get(), summary,
                    reportPath, error);
        }
    }

    private final EvalDatasetLoader loader;
    private final BaselineRetrievalProbe baseline;
    private final WorkflowProbe workflow;
    private final AuthorizationScopeResolver scopes;
    private final EvalCorpusService corpus;
    private final EvalReportPort report;
    private final EvalReportWriter writer;
    private final KwikiDistributedLocks locks;
    private final QaRetrievalBudgets budgets;
    private final int concurrency;
    private final RefusalDetector refusal;
    private final ExecutorService runner = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("eval-run-", 0).daemon(true).factory());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    @Autowired
    public EvalRunService(EvalDatasetLoader loader, BaselineRetrievalProbe baseline, WorkflowProbe workflow,
                          AuthorizationScopeResolver scopes, EvalCorpusService corpus, EvalReportPort report,
                          EvalReportWriter writer, KwikiDistributedLocks locks, QaRetrievalBudgets budgets,
                          @Value("${kwiki.eval.concurrency:2}") int concurrency) {
        this(loader, baseline, workflow, scopes, corpus, report, writer, locks, budgets, concurrency,
                RefusalDetector.DEFAULT_PATTERNS);
    }

    EvalRunService(EvalDatasetLoader loader, BaselineRetrievalProbe baseline, WorkflowProbe workflow,
                   AuthorizationScopeResolver scopes, EvalCorpusService corpus, EvalReportPort report,
                   EvalReportWriter writer, KwikiDistributedLocks locks, QaRetrievalBudgets budgets,
                   int concurrency, List<String> refusalPatterns) {
        this.loader = loader;
        this.baseline = baseline;
        this.workflow = workflow;
        this.scopes = scopes;
        this.corpus = corpus;
        this.report = report;
        this.writer = writer;
        this.locks = locks;
        this.budgets = budgets;
        this.concurrency = Math.max(1, Math.min(concurrency, 8));
        this.refusal = new RefusalDetector(refusalPatterns);
    }

    /** 校验后异步启动；同一时刻只允许一个 run。 */
    public RunStatus start(CurrentUser user, StartRequest request) {
        Prepared prepared = prepare(user, request);
        if (!busy.compareAndSet(false, true)) throw new EvalException(409, "eval-busy");
        runs.put(prepared.state.runId, prepared.state);
        try {
            runner.submit(() -> {
                try {
                    execute(user, request, prepared);
                } finally {
                    busy.set(false);
                }
            });
        } catch (RuntimeException e) {
            busy.set(false);
            throw e;
        }
        return prepared.state.status();
    }

    public RunStatus status(String runId) {
        RunState state = runs.get(runId);
        if (state == null) throw new EvalException(404, "eval-run-not-found");
        return state.status();
    }

    /** 同步执行（测试用），不经过互斥标志。 */
    RunStatus runNow(CurrentUser user, StartRequest request) {
        Prepared prepared = prepare(user, request);
        runs.put(prepared.state.runId, prepared.state);
        execute(user, request, prepared);
        return prepared.state.status();
    }

    private record Prepared(RunState state, List<EvalCase> cases) {
    }

    private Prepared prepare(CurrentUser user, StartRequest request) {
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
        String runName = request.runName() == null || request.runName().isBlank()
                ? size.label() + "-" + RUN_TIME.format(LocalDateTime.now())
                : request.runName();
        return new Prepared(new RunState(runName, size, cases.size()), cases);
    }

    private void execute(CurrentUser user, StartRequest request, Prepared prepared) {
        RunState state = prepared.state;
        AutoCloseable lock = null;
        try {
            if (locks.isAvailable()) {
                lock = locks.acquire("eval-run", Duration.ZERO);
                if (lock == null) {
                    state.error = "eval-busy";
                    state.state = State.FAILED;
                    return;
                }
            }
            ScopeFilter filter = ScopeFilter.from(scopes.resolve(user, Set.of(request.kbId()), Set.of()));
            Map<Long, Integer> pageDocIds = corpus.pageDocIds(user, request.kbId(), EvalCorpusService.DEFAULT_CORPUS);
            Map<String, Object> config = configSnapshot(request, state);
            config.put("sourceDocPages", pageDocIds.size());
            List<CaseResult> results = evaluateAll(user, request, prepared, filter, pageDocIds, config);
            EvalSummary summary = EvalSummaries.summarize(results);
            boolean partial = false;
            if (!request.localOnly()) {
                try {
                    report.flush();
                } catch (RuntimeException e) {
                    partial = true;
                    log.warn("eval report upload incomplete run={} reason={}", state.runName, e.getMessage());
                }
            }
            Path file = writer.write(state.runName, config, summary, results);
            state.summary = summary;
            state.reportPath = file.toString();
            state.state = partial ? State.REPORT_PARTIAL : State.COMPLETED;
        } catch (Exception e) {
            state.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            state.state = State.FAILED;
            log.warn("eval run failed run={} reason={}", state.runName, state.error);
        } finally {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Exception ignored) {
                    // 锁租期到期后 ownership 检查会阻止误解锁
                }
            }
        }
    }

    private List<CaseResult> evaluateAll(CurrentUser user, StartRequest request, Prepared prepared,
                                         ScopeFilter filter, Map<Long, Integer> pageDocIds,
                                         Map<String, Object> config) throws Exception {
        List<EvalCase> cases = prepared.cases;
        CaseResult[] results = new CaseResult[cases.size()];
        ExecutorService pool = Executors.newFixedThreadPool(concurrency,
                Thread.ofPlatform().name("eval-case-", 0).daemon(true).factory());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < cases.size(); i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    CaseResult result = evaluate(user, request.kbId(), filter, pageDocIds, cases.get(index));
                    results[index] = result;
                    if (!request.localOnly()) {
                        report.recordTrace(trace(prepared.state, cases.get(index), result, config));
                    }
                    prepared.state.completed.incrementAndGet();
                }));
            }
            for (Future<?> future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        return List.of(results);
    }

    CaseResult evaluate(CurrentUser user, long kbId, ScopeFilter filter, Map<Long, Integer> pageDocIds,
                        EvalCase evalCase) {
        long started = System.nanoTime();
        try {
            BaselineRetrievalProbe.Result base = baseline.probe(evalCase.query(), filter);
            WorkflowProbe.Result run = workflow.run(user, kbId, evalCase.query(), CASE_TIMEOUT);
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

    private EvalReportPort.TraceRecord trace(RunState state, EvalCase evalCase, CaseResult result,
                                             Map<String, Object> config) {
        String traceId = UUID.nameUUIDFromBytes((state.runName + ":" + evalCase.id())
                .getBytes(StandardCharsets.UTF_8)).toString();
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
                Map.of("query", evalCase.query()), output, metadata, List.of("rag-eval", state.size.label()),
                spans, scores, EvalDatasetSyncService.itemId(state.size, evalCase), state.runName, config);
    }

    private static List<String> aliases(EvalCase evalCase, Set<Integer> groups) {
        return groups.stream().sorted(Comparator.naturalOrder())
                .map(group -> evalCase.keywords().get(group).get(0)).toList();
    }

    private Map<String, Object> configSnapshot(StartRequest request, RunState state) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dataset", state.size.label());
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
