package com.kwiki.infrastructure.orchestration;

import static org.bsc.langgraph4j.StateGraph.*;

import com.kwiki.rag.activity.ActivityEvent;
import com.kwiki.rag.activity.AgenticDebugLogger;
import com.kwiki.rag.answer.*;
import com.kwiki.rag.orchestration.*;
import com.kwiki.rag.quality.*;
import com.kwiki.rag.retrieval.*;
import com.kwiki.rag.rewrite.*;
import com.kwiki.rag.routing.*;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import reactor.core.publisher.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Deterministic QA-gated knowledge workflow. Every knowledge query walks the
 * fixed stage order base-child → base-parent → expanded-child →
 * expanded-parent (each stage regenerates and re-reviews its own candidate)
 * and only a gate-passing candidate is published. Stage exhaustion hands the
 * original question plus the complete failure history to the Query Rewrite
 * Agent; at most 3 query rounds / 2 rewrite calls exist, and content
 * exhaustion answers with the exact insufficient message. The planner/tool
 * path no longer participates in the main chain; its adapters stay available
 * for other callers.
 */
@Service
public class LangGraphAgenticWorkflow implements AgenticWorkflowPort {
    private final RuleFirstRouter router;
    private final CandidateGenerator candidates;
    private final QualityV2AnalyzerPort quality;
    private final FeedbackRewritePort feedbackRewriter;
    private final QaChildRetrievalService childRetrieval;
    private final QaParentFetchService parentFetch;
    private final QaRetrievalBudgets retrievalBudgets;
    private final AuthorizationScopeResolver scopes;
    private final ScopeVersionService versions;
    private final ChatPersistenceService persistence;
    private final MeterRegistry metrics;
    private final AgenticLimits limits;
    private final AgenticDebugLogger debug;
    private final double qaThreshold;
    private final Optional<Tracer> tracer;
    private final Optional<Propagator> propagator;
    private final CompiledGraph<AgentState> graph;
    private final Semaphore permits;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("agentic-deadline").factory());
    private final ExecutorService workers =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("agentic-worker-", 0).factory());

    public LangGraphAgenticWorkflow(
            RuleFirstRouter router,
            CandidateGenerator candidates,
            QualityV2AnalyzerPort quality,
            FeedbackRewritePort feedbackRewriter,
            QaChildRetrievalService childRetrieval,
            QaParentFetchService parentFetch,
            QaRetrievalBudgets retrievalBudgets,
            AuthorizationScopeResolver scopes,
            ScopeVersionService versions,
            ChatPersistenceService persistence,
            MeterRegistry metrics,
            AgenticLimits limits,
            AgenticDebugLogger debug,
            @Value("${kwiki.agentic.qa-threshold:0.80}") double qaThreshold,
            Optional<Tracer> tracer,
            Optional<Propagator> propagator)
            throws GraphStateException {
        this.router = router;
        this.candidates = candidates;
        this.quality = quality;
        this.feedbackRewriter = feedbackRewriter;
        this.childRetrieval = childRetrieval;
        this.parentFetch = parentFetch;
        this.retrievalBudgets = retrievalBudgets;
        this.scopes = scopes;
        this.versions = versions;
        this.persistence = persistence;
        this.metrics = metrics;
        this.limits = limits;
        this.debug = debug;
        this.qaThreshold = qaThreshold;
        this.tracer = tracer;
        this.propagator = propagator;
        permits = new Semaphore(limits.concurrentRuns());
        var g = new StateGraph<AgentState>(AgentState::new);
        Map<String, Consumer<Session>> nodes = new LinkedHashMap<>();
        nodes.put("route", this::route);
        nodes.put("retrieve", this::retrieve);
        nodes.put("generate", this::generate);
        nodes.put("quality", this::quality);
        nodes.put("rewrite", this::rewrite);
        nodes.put("respond", this::respond);
        var destinations = new HashMap<String, String>();
        nodes.keySet().forEach(k -> destinations.put(k, k));
        destinations.put(END, END);
        for (var entry : nodes.entrySet()) {
            String name = entry.getKey();
            g.addNode(
                    name,
                    (state, config) -> {
                        Session s = (Session) config.metadata("session").orElseThrow();
                        try (var binding = s.run.bind();
                                var limit = s.run.limit(nodeDeadline(name))) {
                            s.run.step();
                            s.nodes.add(name);
                            entry.getValue().accept(s);
                            return CompletableFuture.completedFuture(
                                    Map.of("next", s.next, "round", s.queryRound));
                        } catch (Exception e) {
                            return CompletableFuture.failedFuture(e);
                        }
                    });
            g.addConditionalEdges(
                    name,
                    state -> CompletableFuture.completedFuture((String) state.data().get("next")),
                    destinations);
        }
        g.addEdge(START, "route");
        // LangGraph4j 1.8.20 uses the compile-time recursion limit. RunContext
        // remains the authoritative application step budget.
        graph = g.compile(CompileConfig.builder().recursionLimit(limits.steps() + 2).build());
    }

    private java.time.Duration nodeDeadline(String name) {
        return switch (name) {
            case "generate" -> java.time.Duration.ofSeconds(120);
            case "quality" -> limits.modelDeadline().plusSeconds(15);
            case "route" -> java.time.Duration.ofSeconds(10);
            default -> java.time.Duration.ofSeconds(30);
        };
    }

    @Override
    public Flux<ChatStreamEvent> answer(CurrentUser user, String query) {
        return answer(user, query, List.of());
    }

    @Override
    public Flux<ChatStreamEvent> answer(CurrentUser user, String query, List<ChatTurn> conversationHistory) {
        return streamAnswer(user, query, conversationHistory, true, Set.of(), Set.of());
    }

    @Override
    public Flux<ChatStreamEvent> answerInSession(CurrentUser user, String query, List<ChatTurn> conversationHistory) {
        return streamAnswer(user, query, conversationHistory, false, Set.of(), Set.of());
    }

    @Override
    public Flux<ChatStreamEvent> answerInSession(CurrentUser user, String query, List<ChatTurn> conversationHistory,
                                                  Set<Long> kbIds, Set<Long> pageIds) {
        return streamAnswer(user, query, conversationHistory, false, kbIds, pageIds);
    }

    private Flux<ChatStreamEvent> streamAnswer(CurrentUser user, String query, List<ChatTurn> conversationHistory, boolean persistConversation,
                                                Set<Long> kbIds, Set<Long> pageIds) {
        Map<String, String> headers = new HashMap<>();
        String id =
                Optional.ofNullable(org.slf4j.MDC.get("traceId"))
                        .orElse(UUID.randomUUID().toString());
        if (tracer.isPresent() && propagator.isPresent() && tracer.get().currentSpan() != null)
            propagator.get().inject(tracer.get().currentSpan().context(), headers, Map::put);
        String requestId = id;
        return Flux.defer(
                () -> {
                    AtomicReference<Session> reference = new AtomicReference<>();
                    return Flux.<ChatStreamEvent>create(
                                    sink -> {
                                        if (!permits.tryAcquire()) {
                                            sink.next(
                                                    ChatStreamEvent.of(
                                                            "error",
                                                            1,
                                                            requestId,
                                                            Map.of("error", AgenticErrorCodes.AGENT_BUSY)));
                                            sink.complete();
                                            return;
                                        }
                                        AtomicBoolean cancelled = new AtomicBoolean();
                                        AtomicReference<Thread> workerThread =
                                                new AtomicReference<>();
                                        AtomicReference<Future<?>> futureRef =
                                                new AtomicReference<>();
                                        sink.onCancel(
                                                () -> {
                                                    cancelled.set(true);
                                                    var worker = workerThread.get();
                                                    if (worker != null) worker.interrupt();
                                                    var s = reference.get();
                                                    if (s != null) s.run.close();
                                                    var future = futureRef.get();
                                                    if (future != null && s != null)
                                                        future.cancel(true);
                                                });
                                        Future<?> future =
                                                workers.submit(
                                                        () -> {
                                                            workerThread.set(
                                                                    Thread.currentThread());
                                                            Session s = null;
                                                            ScheduledFuture<?> deadline = null;
                                                            try {
                                                                if (cancelled.get()) return;
                                                                var run =
                                                                        new RunContext(
                                                                                requestId,
                                                                                scopes.resolve(user, kbIds, pageIds),
                                                                                versions::current,
                                                                                limits,
                                                                                headers);
                                                                s =
                                                                        new Session(
                                                                                run, user, query,
                                                                                conversationHistory, sink, persistConversation);
                                                                reference.set(s);
                                                                Session active = s;
                                                                deadline =
                                                                        timer.schedule(
                                                                                () -> {
                                                                                    active.fail(
                                                                                            AgenticErrorCodes.TIMEOUT);
                                                                                    run.close();
                                                                                },
                                                                                run.remaining()
                                                                                        .toMillis(),
                                                                                TimeUnit
                                                                                        .MILLISECONDS);
                                                                if (cancelled.get()) {
                                                                    run.close();
                                                                    return;
                                                                }
                                                                try (var bind = run.bind();
                                                                        var cancellation =
                                                                                run.onCancel(
                                                                                        Thread
                                                                                                        .currentThread()
                                                                                                        ::interrupt)) {
                                                                    graph.invoke(
                                                                            Map.of(
                                                                                    "next", "route",
                                                                                    "round", 0),
                                                                            RunnableConfig.builder()
                                                                                    .threadId(
                                                                                            requestId)
                                                                                    .putMetadata(
                                                                                            "session",
                                                                                            s)
                                                                                    .build());
                                                                }
                                                            } catch (Exception e) {
                                                                if (s != null) s.fail(code(e));
                                                                else if (!sink.isCancelled()) {
                                                                    sink.next(
                                                                            ChatStreamEvent.of(
                                                                                    "error",
                                                                                    1,
                                                                                    requestId,
                                                                                    Map.of(
                                                                                            "error",
                                                                                            AgenticErrorCodes.INTERNAL_ERROR)));
                                                                    sink.complete();
                                                                }
                                                            } finally {
                                                                if (deadline != null)
                                                                    deadline.cancel(false);
                                                                if (s != null) {
                                                                    if (cancelled.get()
                                                                            && !s.terminal.get())
                                                                        s.outcome = AgenticErrorCodes.CANCELLED;
                                                                    Thread.interrupted();
                                                                    s.audit();
                                                                    s.run.close();
                                                                }
                                                                permits.release();
                                                            }
                                                        });
                                        futureRef.set(future);
                                        if (cancelled.get() && reference.get() != null)
                                            future.cancel(true);
                                    },
                                    FluxSink.OverflowStrategy.ERROR)
                            .onBackpressureBuffer(
                                    limits.bufferSize(),
                                    v -> {
                                        var s = reference.get();
                                        if (s != null) s.run.close();
                                    })
                            .timeout(limits.timeout())
                            .onErrorResume(
                                    error -> {
                                        var s = reference.get();
                                        if (s != null) s.run.close();
                                        return Flux.just(
                                                ChatStreamEvent.of(
                                                        "error",
                                                        s == null ? 1 : s.seq.incrementAndGet(),
                                                        requestId,
                                                        Map.of(
                                                                "error",
                                                                error instanceof TimeoutException
                                                                        ? AgenticErrorCodes.TIMEOUT
                                                                        : AgenticErrorCodes.BACKPRESSURE_OVERFLOW)));
                                    });
                });
    }

    private static String code(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof RunFailure) return c.getMessage();
            if (c instanceof HybridRetrievalOrchestrator.StaleScopeException)
                return AgenticErrorCodes.AUTHORIZATION_CHANGED;
            if (c instanceof ConcurrentRecallService.RetrievalBranchException)
                return AgenticErrorCodes.RETRIEVAL_FAILED;
            if (c instanceof RetrievalLifecycleService.LifecycleFilterException)
                return AgenticErrorCodes.RETRIEVAL_FAILED;
        }
        return AgenticErrorCodes.INTERNAL_ERROR;
    }

    // ------------------------------------------------------------------
    // nodes
    // ------------------------------------------------------------------

    private void route(Session s) {
        long startedAt = s.nowMs();
        s.emitActivity(ActivityEvent.started(stepId(s, "route"), null, s.queryRound, null,
                ActivityEvent.Phase.ROUTE, startedAt, "正在理解问题"));
        String normalized = QueryNormalizer.normalize(s.query);
        if (normalized.isBlank() || normalized.length() > 1000)
            throw new RunFailure(AgenticErrorCodes.INVALID_QUERY);
        s.currentQuery = normalized;
        if (normalized.matches("(?i)(你好|您好|hello|hi|帮助|help)[!！。?？]*")) {
            s.message = "你好，我可以根据你有权访问的 Wiki 内容检索资料并回答问题。";
            s.outcome = "direct";
            s.next = "respond";
            s.emitActivity(ActivityEvent.finished(stepId(s, "route"), null, s.queryRound, null,
                    ActivityEvent.Phase.ROUTE, ActivityEvent.Status.COMPLETED, startedAt,
                    s.sinceMs(startedAt), "问候直接回复", Map.of(), null));
            return;
        }
        s.route = router.route(s.query);
        if (normalized.matches("(它|这个|那个|上面|刚才)(呢|是什么|怎么用|怎么样)[？?]?")) {
            s.message = "请说明你指的是哪个页面、产品或主题。";
            s.outcome = "clarification";
            s.next = "respond";
            s.emitActivity(ActivityEvent.finished(stepId(s, "route"), null, s.queryRound, null,
                    ActivityEvent.Phase.ROUTE, ActivityEvent.Status.COMPLETED, startedAt,
                    s.sinceMs(startedAt), "需要澄清", Map.of(), null));
            return;
        }
        s.debugStage("route", "complete", 0, () -> Map.of(
                "query", AgenticDebugLogger.boundedQuery(s.currentQuery),
                "intent", s.route.intent().name()));
        s.emitActivity(ActivityEvent.finished(stepId(s, "route"), null, s.queryRound, null,
                ActivityEvent.Phase.ROUTE, ActivityEvent.Status.COMPLETED, startedAt,
                s.sinceMs(startedAt), "已识别为知识检索", Map.of(), null));
        // Round 1 uses the normalized original query verbatim: no semantic
        // rewrite before the first retrieval.
        s.next = "retrieve";
    }

    /** Executes the retrieval half of the current stage (child or parent). */
    private void retrieve(Session s) {
        if (s.stage.isParentStage()) {
            retrieveParents(s);
        } else {
            retrieveChildren(s);
        }
    }

    private void retrieveChildren(Session s) {
        s.run.authorize();
        s.hybridRetrievalUsed++;
        if (s.hybridRetrievalUsed > limits.hybridRetrievals()) {
            throw new RunFailure("retrieval-budget-exhausted");
        }
        var budget = s.stage.isExpandedStage()
                ? retrievalBudgets.expanded() : retrievalBudgets.base();
        String step = stepId(s, "retrieval");
        long startedAt = s.nowMs();
        s.emitActivity(ActivityEvent.started(step, null, s.queryRound, s.stage.name(),
                ActivityEvent.Phase.RETRIEVAL, startedAt,
                s.stage.isExpandedStage() ? "扩大范围重新检索" : "正在检索知识库"));
        QaChildRetrievalService.StageOutcome outcome;
        try {
            outcome = childRetrieval.retrieveChildren(
                    s.run.scope, s.run, s.currentQuery, budget, s.embeddingCache);
        } catch (RunFailure e) {
            throw e;
        }
        s.lastDegradations = outcome.degradations();
        s.children = trimToBudget(outcome.children(), budget.childCharBudget());
        s.parentEvidence = List.of();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("branchTopK", budget.branchTopK());
        metrics.put("finalTopK", budget.finalTopK());
        metrics.put("fusedCandidateCount", outcome.fusedCandidateCount());
        metrics.put("retainedChildCount", s.children.size());
        metrics.put("sourceTitles", sourceTitles(s.children));
        if (!s.lastDegradations.isEmpty()) {
            metrics.put("degradedBranches", s.lastDegradations);
        }
        s.debugStage("retrieval", s.children.isEmpty() ? "no-evidence" : "complete",
                outcome.elapsedMs(), () -> Map.of(
                        "query", AgenticDebugLogger.boundedQuery(s.currentQuery),
                        "branchTopK", budget.branchTopK(),
                        "finalTopK", budget.finalTopK(),
                        "fusedCandidateCount", outcome.fusedCandidateCount(),
                        "retainedChildCount", s.children.size(),
                        "degradations", s.lastDegradations));
        if (s.children.isEmpty()) {
            // Successful zero hits: never spin the generation model on nothing.
            s.recordStageFailure("no-evidence");
            s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, s.stage.name(),
                    ActivityEvent.Phase.RETRIEVAL, ActivityEvent.Status.SKIPPED, startedAt,
                    s.sinceMs(startedAt), "未命中任何内容", metrics, "no-evidence"));
            advanceRecovery(s);
            return;
        }
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, s.stage.name(),
                ActivityEvent.Phase.RETRIEVAL, ActivityEvent.Status.COMPLETED, startedAt,
                s.sinceMs(startedAt),
                s.stage.isExpandedStage()
                        ? "扩大检索命中 " + s.children.size() + " 个片段"
                        : "检索命中 " + s.children.size() + " 个片段",
                metrics,
                s.lastDegradations.isEmpty() ? null : s.lastDegradations.get(0)));
        s.next = "generate";
    }

    private void retrieveParents(Session s) {
        s.run.authorize();
        s.parentFetchUsed++;
        if (s.parentFetchUsed > limits.parentFetches()) {
            throw new RunFailure("parent-budget-exhausted");
        }
        var budget = s.stage.isExpandedStage()
                ? retrievalBudgets.expanded() : retrievalBudgets.base();
        String step = stepId(s, "parent");
        long startedAt = s.nowMs();
        s.emitActivity(ActivityEvent.started(step, null, s.queryRound, s.stage.name(),
                ActivityEvent.Phase.PARENT_FETCH, startedAt, "正在补充完整上下文"));
        var outcome = parentFetch.fetchParents(
                s.run.scope, s.run, s.children, budget.parentLimit(),
                retrievalBudgets.parentCharBudget, s.usedParentKeys);
        if (outcome.skipped()) {
            s.recordStageFailure(outcome.reason());
            s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, s.stage.name(),
                    ActivityEvent.Phase.PARENT_FETCH, ActivityEvent.Status.SKIPPED, startedAt,
                    s.sinceMs(startedAt), "无新的完整上下文", Map.of(), outcome.reason()));
            s.debugStage("parent-fetch", "skipped", outcome.elapsedMs(), () -> Map.of(
                    "reason", outcome.reason()));
            advanceRecovery(s);
            return;
        }
        s.parentEvidence = outcome.evidence();
        s.usedParentKeys.addAll(QaParentFetchService.keysOf(outcome.evidence()));
        s.debugStage("parent-fetch", "complete", outcome.elapsedMs(), () -> Map.of(
                "parentCount", outcome.parentCount()));
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, s.stage.name(),
                ActivityEvent.Phase.PARENT_FETCH, ActivityEvent.Status.COMPLETED, startedAt,
                s.sinceMs(startedAt), "补充 " + outcome.parentCount() + " 个完整段落",
                Map.of("parentCount", outcome.parentCount()), null));
        s.next = "generate";
    }

    private void generate(Session s) {
        s.generationsUsed++;
        if (s.generationsUsed > limits.generations()) {
            throw new RunFailure("generation-budget-exhausted");
        }
        String step = stepId(s, "generate");
        long startedAt = s.nowMs();
        s.emitActivity(ActivityEvent.started(step, null, s.queryRound, s.stage.name(),
                ActivityEvent.Phase.GENERATION, startedAt, "正在生成候选回答"));
        String context = s.stage.isParentStage()
                ? parentContext(s.parentEvidence)
                : childContext(s.children);
        var generated = candidates.generate(
                s.run, s.query, s.currentQuery, s.stage, s.children, s.parentEvidence, context);
        s.candidate = generated.candidate();
        s.debugStage("generation", "complete", generated.elapsedMs(), () -> Map.of(
                "candidateId", s.candidate.candidateId(),
                "evidenceLevel", s.candidate.evidenceLevel().name(),
                "chars", s.candidate.contentLength(),
                "summary", AgenticDebugLogger.boundedSummary(s.candidate.content())));
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, s.stage.name(),
                ActivityEvent.Phase.GENERATION, ActivityEvent.Status.COMPLETED, startedAt,
                s.sinceMs(startedAt), "候选回答已生成，正在质量评审",
                Map.of("candidateId", s.candidate.candidateId(),
                        "chars", s.candidate.contentLength()),
                null));
        s.next = "quality";
    }

    private void quality(Session s) {
        s.qualityUsed++;
        if (s.qualityUsed > limits.qualityReviews()) {
            throw new RunFailure("quality-budget-exhausted");
        }
        String step = stepId(s, "quality");
        long startedAt = s.nowMs();
        var input = new QualityV2Input(s.query, s.currentQuery, s.candidate, List.of());
        QualityAssessment assessment;
        try {
            assessment = quality.assess(input);
        } catch (RunFailure e) {
            s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, s.stage.name(),
                    ActivityEvent.Phase.QUALITY, ActivityEvent.Status.FAILED, startedAt,
                    s.sinceMs(startedAt), "质量评审暂不可用", Map.of(), e.getMessage()));
            throw e;
        }
        var evidenceIds = evidenceIdsOf(s.candidate);
        boolean passes = assessment.gatePasses(qaThreshold, evidenceIds);
        s.debugStage("quality", passes ? "pass" : "fail", 0, () -> Map.of(
                "candidateId", s.candidate.candidateId(),
                "threshold", qaThreshold,
                "relevance", assessment.relevance(),
                "coverage", assessment.coverage(),
                "faithfulness", assessment.faithfulness(),
                "reasonCode", assessment.reasonCode(),
                "missingAspects", assessment.missingAspects(),
                "unsupportedClaims", assessment.unsupportedClaims()));
        if (passes) {
            s.assessment = assessment;
            s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, s.stage.name(),
                    ActivityEvent.Phase.QUALITY, ActivityEvent.Status.COMPLETED, startedAt,
                    s.sinceMs(startedAt), "质量评审通过",
                    Map.of("supportedEvidenceCount", assessment.supportedEvidenceIds().size()),
                    null));
            publish(s);
            return;
        }
        s.recordStageFailure(assessment.reasonCode());
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, s.stage.name(),
                ActivityEvent.Phase.QUALITY, ActivityEvent.Status.COMPLETED, startedAt,
                s.sinceMs(startedAt), "质量评审未通过：" + businessReason(assessment),
                Map.of("reasonCode", assessment.reasonCode()),
                assessment.reasonCode()));
        advanceRecovery(s);
    }

    /** Publishes exactly the reviewed candidate text; no second generation. */
    private void publish(Session s) {
        // Output boundary: re-validate scope/evidence validity before any byte
        // leaves the server.
        s.run.authorize();
        String text = s.candidate.content();
        for (String token : paragraphs(text)) {
            s.run.check();
            s.text.append(token);
            s.emit("token", Map.of("text", token));
        }
        s.emitCitations();
        s.outcome = "completed";
        s.debugTerminal("completed", null, () -> Map.of(
                "candidateId", s.candidate.candidateId(),
                "queryRound", s.queryRound,
                "stage", s.stage.name()));
        s.done(Map.of(
                "outcome", s.outcome,
                "citationCount", s.citationCount,
                "queryRound", s.queryRound,
                "attemptStage", s.stage.name()));
        s.next = END;
    }

    private void rewrite(Session s) {
        // Rewrite budget / query-round budget exhausted → exact refusal.
        if (s.rewritesUsed >= limits.rewrites()
                || s.queryRound >= limits.queryRounds()
                || !s.run.canModel()) {
            s.finishInsufficient("recovery-exhausted");
            return;
        }
        var budget = retrievalBudgets.base();
        FeedbackRewriteInput input = new FeedbackRewriteInput(
                s.query,
                s.rewriteHistory,
                s.currentQuery,
                s.stageFailures,
                s.latestReason,
                budget.finalTopK(),
                retrievalBudgets.expanded().finalTopK(),
                true,
                s.conversationHistory);
        String step = stepId(s, "rewrite");
        long startedAt = s.nowMs();
        s.emitActivity(ActivityEvent.started(step, null, s.queryRound, null,
                ActivityEvent.Phase.REWRITE, startedAt, "正在根据评审反馈调整检索问题"));
        s.debugStage("rewrite", "start", 0, () -> Map.of(
                "originalQuery", AgenticDebugLogger.boundedQuery(s.query),
                "previousRewrites", s.rewriteHistory.stream()
                        .map(AgenticDebugLogger::boundedQuery).toList(),
                "lastQuery", AgenticDebugLogger.boundedQuery(s.currentQuery),
                "qaFailures", s.stageFailures.stream()
                        .map(AgenticDebugLogger::boundedReason).toList(),
                "topKBefore", budget.finalTopK(),
                "topKAfter", retrievalBudgets.expanded().finalTopK()));
        s.rewritesUsed++;
        var rewritten = feedbackRewriter.rewrite(input);
        if (rewritten.isEmpty() && s.rewritesUsed < limits.rewrites() && s.run.canModel()) {
            // Invalid/echoing rewrite consumed one call; one retry with the
            // validation failure attached when budget remains.
            s.recordStageFailure("rewrite-validation-failed");
            FeedbackRewriteInput retry = new FeedbackRewriteInput(
                    s.query, s.rewriteHistory, s.currentQuery, s.stageFailures,
                    s.latestReason, budget.finalTopK(),
                    retrievalBudgets.expanded().finalTopK(), true, s.conversationHistory);
            s.debugStage("rewrite", "retry", 0, () -> Map.of(
                    "reason", "invalid-or-echoing-output"));
            s.rewritesUsed++;
            rewritten = feedbackRewriter.rewrite(retry);
        }
        if (rewritten.isEmpty()) {
            s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, null,
                    ActivityEvent.Phase.REWRITE, ActivityEvent.Status.FAILED, startedAt,
                    s.sinceMs(startedAt), "改写未产生可用的新问题", Map.of(),
                    "rewrite-unavailable"));
            s.finishInsufficient("rewrite-exhausted");
            return;
        }
        String newQuery = rewritten.get();
        s.rewriteHistory.add(newQuery);
        s.currentQuery = newQuery;
        s.queryRound++;
        s.stage = AttemptStage.BASE_CHILD; // new round resets the base TopK
        s.usedParentKeys.clear();
        s.stageFailures.clear();
        s.debugStage("rewrite", "complete", 0, () -> Map.of(
                "newQuery", AgenticDebugLogger.boundedQuery(newQuery),
                "queryRound", s.queryRound,
                "rewritesUsed", s.rewritesUsed));
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound - 1, null,
                ActivityEvent.Phase.REWRITE, ActivityEvent.Status.COMPLETED, startedAt,
                s.sinceMs(startedAt), "已改写检索问题，开始第 " + s.queryRound + " 轮检索",
                Map.of("queryRound", s.queryRound), null));
        s.next = "retrieve";
    }

    private void respond(Session s) {
        s.run.authorize();
        if (AgenticErrorCodes.INSUFFICIENT.equals(s.outcome)) {
            s.message = AgenticErrorCodes.INSUFFICIENT_MESSAGE;
        }
        s.text.append(s.message);
        s.emit("token", Map.of("text", s.message));
        s.emit("citations", Map.of("citations", List.of()));
        s.debugTerminal(s.outcome, s.outcome, () -> Map.of(
                "queryRound", s.queryRound,
                "stage", s.stage.name()));
        s.emitActivity(ActivityEvent.finished(stepId(s, "final"), null, s.queryRound,
                s.stage.name(), ActivityEvent.Phase.FINAL, ActivityEvent.Status.COMPLETED,
                s.nowMs(), 0L,
                AgenticErrorCodes.INSUFFICIENT.equals(s.outcome) ? "检索结束 · 未找到足够依据" : s.outcome,
                Map.of(), s.outcome));
        s.done(Map.of(
                "outcome", s.outcome,
                "noEvidence", AgenticErrorCodes.INSUFFICIENT.equals(s.outcome),
                "message", s.message));
        s.next = END;
    }

    // ------------------------------------------------------------------
    // state machine helpers
    // ------------------------------------------------------------------

    /**
     * Fixed recovery order: base-child → base-parent → expanded-child →
     * expanded-parent → rewrite. Expansion happens once per query round.
     */
    private void advanceRecovery(Session s) {
        AttemptStage next = s.stage.next();
        if (next == null) {
            s.latestReason = s.stageFailures.isEmpty() ? "unknown" : latest(s.stageFailures);
            s.next = "rewrite";
            return;
        }
        if (next == AttemptStage.EXPANDED_CHILD) {
            s.emitActivity(ActivityEvent.finished(stepId(s, "expand"), null, s.queryRound,
                    s.stage.name(), ActivityEvent.Phase.EXPANSION, ActivityEvent.Status.COMPLETED,
                    s.nowMs(), 0L, "扩大检索范围（" + retrievalBudgets.base().finalTopK()
                            + " → " + retrievalBudgets.expanded().finalTopK() + "）",
                    Map.of("topKBefore", retrievalBudgets.base().finalTopK(),
                            "topKAfter", retrievalBudgets.expanded().finalTopK()),
                    null));
        }
        s.stage = next;
        s.next = "retrieve";
    }

    private static String latest(List<String> failures) {
        return failures.isEmpty() ? "" : failures.get(failures.size() - 1);
    }

    private static String businessReason(QualityAssessment assessment) {
        if (!assessment.unsupportedClaims().isEmpty()) return "存在证据不支持的陈述";
        if (assessment.minScore() < 0.5) return "与问题相关性或覆盖不足";
        if (assessment.missingAspects().isEmpty()) return "内容质量未达发布门槛";
        return "缺少关键方面：" + String.join("；", assessment.missingAspects());
    }

    /** Up to four distinct authorized source titles for the activity timeline. */
    private static List<String> sourceTitles(List<ChildEvidence> children) {
        return children.stream()
                .map(child -> child.headingPath() == null || child.headingPath().isBlank()
                        ? "未命名段落" : child.headingPath())
                .distinct()
                .limit(4)
                .toList();
    }

    private List<ChildEvidence> trimToBudget(List<ChildEvidence> children, long charBudget) {
        List<ChildEvidence> retained = new ArrayList<>();
        long remaining = charBudget;
        for (ChildEvidence child : children) {
            if (remaining <= 0) {
                break;
            }
            retained.add(child);
            remaining -= child.content().length();
        }
        return List.copyOf(retained);
    }

    private String childContext(List<ChildEvidence> children) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < children.size(); i++) {
            context.append("[P").append(i).append("] ")
                    .append(children.get(i).content()).append("\n");
        }
        return context.toString();
    }

    private String parentContext(List<ParentEvidence> parents) {
        StringBuilder context = new StringBuilder();
        for (ParentEvidence parent : parents) {
            context.append("[P").append(parent.firstHitOrder()).append("] ")
                    .append(parent.body()).append("\n");
        }
        return context.toString();
    }

    private java.util.Set<String> evidenceIdsOf(CandidateAnswer candidate) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (candidate.evidenceLevel() == CandidateAnswer.EvidenceLevel.PARENT) {
            candidate.parentEvidence().forEach(parent -> ids.add(parent.parentChunkKey()));
        } else {
            candidate.retainedChildren().forEach(child -> ids.add(child.chunkKey()));
        }
        return ids;
    }

    /** Splits the reviewed candidate into paragraph-sized publish chunks. */
    static List<String> paragraphs(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : text.split("(?<=\n)")) {
            if (!part.isEmpty()) parts.add(part);
        }
        if (parts.isEmpty()) parts.add(text);
        return parts;
    }

    private String stepId(Session s, String phase) {
        return "q" + s.queryRound + "-" + s.stage.name().toLowerCase(Locale.ROOT) + "-" + phase;
    }

    private final class Session {
        final RunContext run;
        final boolean persistConversation;
        final CurrentUser user;
        final String query;
        final List<ChatTurn> conversationHistory;
        final FluxSink<ChatStreamEvent> sink;
        final AtomicLong seq = new AtomicLong();
        final AtomicBoolean terminal = new AtomicBoolean(), audited = new AtomicBoolean();
        final StringBuilder text = new StringBuilder();
        final List<String> nodes = new ArrayList<>();
        final Map<String, float[]> embeddingCache = new LinkedHashMap<>();
        final java.util.Set<String> usedParentKeys = new java.util.LinkedHashSet<>();
        final List<String> rewriteHistory = new ArrayList<>();
        final List<String> stageFailures = new ArrayList<>();
        List<ChildEvidence> children = List.of();
        List<ParentEvidence> parentEvidence = List.of();
        List<String> lastDegradations = List.of();
        CandidateAnswer candidate;
        QualityAssessment assessment;
        RetrievalPlan route;
        AttemptStage stage = AttemptStage.BASE_CHILD;
        String currentQuery;
        String latestReason = "";
        String next = "route", outcome = AgenticErrorCodes.INSUFFICIENT,
                message = AgenticErrorCodes.INSUFFICIENT_MESSAGE;
        int queryRound = 1, rewritesUsed, hybridRetrievalUsed, parentFetchUsed,
                generationsUsed, qualityUsed, citationCount;

        Session(RunContext run, CurrentUser user, String query, List<ChatTurn> conversationHistory, FluxSink<ChatStreamEvent> sink, boolean persistConversation) {
            this.persistConversation = persistConversation;
            this.run = run;
            this.user = user;
            this.query = query;
            this.conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
            this.currentQuery = query == null ? "" : query;
            this.sink = sink;
        }

        synchronized void emit(String type, Map<String, Object> payload) {
            if (terminal.get() || sink.isCancelled()) return;
            var data = new LinkedHashMap<String, Object>(payload);
            data.put("round", queryRound);
            sink.next(ChatStreamEvent.of(type, seq.incrementAndGet(), run.requestId, data));
        }

        synchronized void emitActivity(ActivityEvent event) {
            if (terminal.get() || sink.isCancelled()) return;
            emit("activity", event.toPayload());
        }

        synchronized void done(Map<String, Object> data) {
            if (terminal.get()) return;
            emit("done", data);
            terminal.set(true);
            sink.complete();
        }

        synchronized void fail(String errorCode) {
            if (terminal.get() || sink.isCancelled()) return;
            outcome = errorCode;
            emit("error", Map.of("error", errorCode));
            terminal.set(true);
            sink.complete();
        }

        void finishInsufficient(String code) {
            outcome = AgenticErrorCodes.INSUFFICIENT;
            latestReason = code;
            next = "respond";
        }

        void recordStageFailure(String reason) {
            if (reason != null && !reason.isBlank()) {
                stageFailures.add(reason);
                latestReason = reason;
            }
        }

        long nowMs() {
            return System.currentTimeMillis();
        }

        Long sinceMs(long startedAt) {
            return Math.max(0, System.currentTimeMillis() - startedAt);
        }

        void emitCitations() {
            run.authorize();
            var used = new HashSet<Integer>();
            var matcher = Pattern.compile("\\[P(\\d+)]").matcher(text);
            while (matcher.find()) used.add(Integer.parseInt(matcher.group(1)));
            var valid = validCitationIds();
            if (!valid.containsAll(used)) throw new RunFailure(AgenticErrorCodes.ANSWER_VALIDATION_FAILED);
            var citations = new ArrayList<Map<String, Object>>();
            if (candidate != null
                    && candidate.evidenceLevel() == CandidateAnswer.EvidenceLevel.PARENT) {
                for (var parent : parentEvidence) {
                    if (!used.contains(parent.firstHitOrder())) continue;
                    for (ChunkHit child : parent.matchedChildren()) {
                        var item = new LinkedHashMap<String, Object>();
                        item.put("childChunkKey", child.chunkKey());
                        item.put("parentChunkKey", parent.parentChunkKey());
                        item.put("resourceType", parent.resourceType());
                        item.put("resourceId", parent.resourceId());
                        item.put("revisionId", parent.revisionId());
                        item.put("headingPath", child.headingPath());
                        item.put("charStart", child.charStart());
                        item.put("charEnd", child.charEnd());
                        item.put("excerpt", excerpt(child.content(), 120));
                        citations.add(item);
                    }
                }
            } else if (candidate != null) {
                for (int i = 0; i < children.size(); i++) {
                    if (!used.contains(i)) continue;
                    ChildEvidence child = children.get(i);
                    var item = new LinkedHashMap<String, Object>();
                    item.put("childChunkKey", child.chunkKey());
                    item.put("parentChunkKey", child.parentChunkKey());
                    item.put("resourceType", child.resourceType());
                    item.put("resourceId", child.resourceId());
                    item.put("revisionId", child.revisionId());
                    item.put("headingPath", child.headingPath());
                    item.put("charStart", child.charStart());
                    item.put("charEnd", child.charEnd());
                    item.put("excerpt", excerpt(child.content(), 120));
                    citations.add(item);
                }
            }
            citationCount = citations.size();
            emit("citations", Map.of("citations", citations));
        }

        private static String excerpt(String content, int max) {
            if (content == null) return "";
            return content.substring(0, Math.min(max, content.length()));
        }

        private java.util.Set<Integer> validCitationIds() {
            java.util.Set<Integer> valid = new java.util.HashSet<>();
            if (candidate != null
                    && candidate.evidenceLevel() == CandidateAnswer.EvidenceLevel.PARENT) {
                parentEvidence.forEach(parent -> valid.add(parent.firstHitOrder()));
            } else {
                for (int i = 0; i < children.size(); i++) valid.add(i);
            }
            return valid;
        }

        void debugStage(String phase, String status, long elapsedMs,
                        java.util.function.Supplier<Map<String, Object>> fields) {
            debug.stage(run, phase, queryRound, stage.name(), phase, status, elapsedMs, fields);
        }

        void debugTerminal(String outcomeCode, String reason,
                           java.util.function.Supplier<Map<String, Object>> fields) {
            debug.terminal(run, outcomeCode, reason, fields);
        }

        void audit() {
            if (audited.compareAndSet(false, true)) {
                var trace = new LinkedHashMap<String, Object>(run.stats());
                trace.put("outcome", outcome);
                trace.put("queryRounds", queryRound);
                trace.put("rewrites", rewritesUsed);
                trace.put("stages", stageFailures);
                trace.put("nodes", List.copyOf(nodes));
                trace.put("toolSchemaVersion", "2");
                if (persistConversation) persistence.persistTurn(
                        run.requestId, user.id(), query, text.toString(), trace, run.requestId);
                else persistence.persistTrace(user.id(), trace, run.requestId);
                metrics.counter("kwiki_answer_total", "outcome", outcome).increment();
            }
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        workers.shutdownNow();
        timer.shutdownNow();
    }
}
