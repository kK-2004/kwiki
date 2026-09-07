package com.kwiki.infrastructure.orchestration;

import static org.bsc.langgraph4j.StateGraph.*;

import com.kwiki.rag.answer.*;
import com.kwiki.rag.orchestration.*;
import com.kwiki.rag.quality.*;
import com.kwiki.rag.retrieval.*;
import com.kwiki.rag.rewrite.*;
import com.kwiki.rag.routing.*;
import com.kwiki.rag.tool.*;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Service;

import reactor.core.publisher.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.Pattern;

/**
 * Core graph controls every transition; metadata is trusted, request-local and never checkpointed.
 */
@Service
public class LangGraphAgenticWorkflow implements AgenticWorkflowPort {
    private final RuleFirstRouter router;
    private final QueryRewriteOrchestrator rewrites;
    private final HybridRetrievalOrchestrator retrieval;
    private final EvidenceAssembler assembler;
    private final AnswerLlmPort answer;
    private final QualityAnalyzerPort quality;
    private final RetrievalPlannerPort planner;
    private final ToolRegistry registry;
    private final ManualToolDispatcher dispatcher;
    private final AuthorizationScopeResolver scopes;
    private final ScopeVersionService versions;
    private final ChatPersistenceService persistence;
    private final MeterRegistry metrics;
    private final AgenticLimits limits;
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
            QueryRewriteOrchestrator rewrites,
            HybridRetrievalOrchestrator retrieval,
            EvidenceAssembler assembler,
            AnswerLlmPort answer,
            QualityAnalyzerPort quality,
            RetrievalPlannerPort planner,
            ToolRegistry registry,
            ManualToolDispatcher dispatcher,
            AuthorizationScopeResolver scopes,
            ScopeVersionService versions,
            ChatPersistenceService persistence,
            MeterRegistry metrics,
            AgenticLimits limits,
            Optional<Tracer> tracer,
            Optional<Propagator> propagator)
            throws GraphStateException {
        this.router = router;
        this.rewrites = rewrites;
        this.retrieval = retrieval;
        this.assembler = assembler;
        this.answer = answer;
        this.quality = quality;
        this.planner = planner;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.scopes = scopes;
        this.versions = versions;
        this.persistence = persistence;
        this.metrics = metrics;
        this.limits = limits;
        this.tracer = tracer;
        this.propagator = propagator;
        permits = new Semaphore(limits.concurrentRuns());
        var g = new StateGraph<AgentState>(AgentState::new);
        Map<String, Consumer<Session>> nodes = new LinkedHashMap<>();
        nodes.put("route", this::route);
        nodes.put("rewrite", this::rewrite);
        nodes.put("plan", this::plan);
        nodes.put("validate", this::validate);
        nodes.put("retrieve", this::retrieve);
        nodes.put("quality", this::quality);
        nodes.put("generate", this::generate);
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
                                var limit =
                                        s.run.limit(
                                                java.time.Duration.ofSeconds(
                                                        name.equals("generate")
                                                                ? 120
                                                                : name.equals("route")
                                                                        ? 10
                                                                        : 15))) {
                            s.run.step();
                            s.nodes.add(name);
                            entry.getValue().accept(s);
                            return CompletableFuture.completedFuture(
                                    Map.of("next", s.next, "round", s.round));
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
        // LangGraph4j 1.8.20 uses the compile-time recursion limit. Keep the
        // small allowance for START/END
        // transitions while RunContext remains the authoritative application
        // step budget.
        graph = g.compile(CompileConfig.builder().recursionLimit(limits.steps() + 2).build());
    }

    @Override
    public Flux<ChatStreamEvent> answer(CurrentUser user, String query) {
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
                                                            Map.of("error", "agent-busy")));
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
                                                                                scopes.resolve(
                                                                                        user),
                                                                                versions::current,
                                                                                limits,
                                                                                headers);
                                                                s =
                                                                        new Session(
                                                                                run, user, query,
                                                                                sink);
                                                                reference.set(s);
                                                                Session active = s;
                                                                deadline =
                                                                        timer.schedule(
                                                                                () -> {
                                                                                    active.fail(
                                                                                            "timeout");
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
                                                                                            "internal-error")));
                                                                    sink.complete();
                                                                }
                                                            } finally {
                                                                if (deadline != null)
                                                                    deadline.cancel(false);
                                                                if (s != null) {
                                                                    if (cancelled.get()
                                                                            && !s.terminal.get())
                                                                        s.outcome = "cancelled";
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
                                                                        ? "timeout"
                                                                        : "backpressure-overflow")));
                                    });
                });
    }

    private static String code(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof RunFailure) return c.getMessage();
            if (c instanceof HybridRetrievalOrchestrator.StaleScopeException)
                return "authorization-changed";
            if (c instanceof ConcurrentRecallService.RetrievalBranchException)
                return "retrieval-failed";
        }
        return "internal-error";
    }

    private void route(Session s) {
        s.emit("route", Map.of("status", "started"));
        String normalized = QueryNormalizer.normalize(s.query);
        if (normalized.isBlank() || normalized.length() > 1000)
            throw new RunFailure("invalid-query");
        if (normalized.matches("(?i)(你好|您好|hello|hi|帮助|help)[!！。?？]*")) {
            s.message = "你好，我可以根据你有权访问的 Wiki 内容检索资料并回答问题。";
            s.outcome = "direct";
            s.next = "respond";
            return;
        }
        s.route = router.route(s.query);
        s.emit(
                "route",
                Map.of("intent", s.route.intent().name(), "source", s.route.source().name()));
        if (normalized.matches("(它|这个|那个|上面|刚才)(呢|是什么|怎么用|怎么样)[？?]?")) {
            s.message = "请说明你指的是哪个页面、产品或主题。";
            s.outcome = "clarification";
            s.next = "respond";
            return;
        }
        s.next = "rewrite";
    }

    private void rewrite(Session s) {
        if (s.round == 0) {
            var rewritten = rewrites.rewrite(s.query, s.route, List.of());
            s.queries = rewritten.effectiveQueries();
            s.emit(
                    "rewrite",
                    Map.of("mode", rewritten.mode().name(), "queryCount", s.queries.size()));
        } else {
            if (!s.decision.suggestedQueries().isEmpty()) s.queries = s.decision.suggestedQueries();
            s.emit("retry", Map.of("reasonCode", s.decision.reasonCode()));
            s.emit("rewrite", Map.of("mode", "QA_FEEDBACK", "queryCount", s.queries.size()));
        }
        s.repair = 0;
        s.validationError = "";
        s.fallback = false;
        s.next = "plan";
    }

    private void plan(Session s) {
        s.run.authorize();
        try {
            s.calls =
                    planner.plan(
                            s.query,
                            s.queries,
                            s.decision.missingAspects(),
                            s.history,
                            s.validationError);
        } catch (Exception e) {
            s.run.check();
            s.calls = List.of();
            s.validationError = "planner-unavailable";
        }
        s.next = "validate";
    }

    private void validate(Session s) {
        try {
            s.validated =
                    registry.validateBatch(s.calls, retrieval.budgets(), limits.callsPerRound());
            if (!s.run.canTools(s.calls.size())) {
                s.finishInsufficient("tool-budget-exhausted");
                return;
            }
            var signatures = s.validated.stream().map(c -> c.arguments().signature()).toList();
            if (new HashSet<>(signatures).size() != signatures.size()
                    || signatures.stream().anyMatch(s.attempts::contains)) {
                s.finishInsufficient("no-progress");
                return;
            }
            s.attempts.addAll(signatures);
            s.next = "retrieve";
        } catch (RunFailure e) {
            if (s.fallback) throw e;
            if (s.repair++ == 0 && s.run.canModel()) {
                s.validationError = e.getMessage();
                s.next = "plan";
                return;
            }
            s.fallback = true;
            s.calls =
                    List.of(
                            new ToolCall(
                                    "fallback-" + s.round,
                                    "es_search",
                                    ToolRegistry.json(
                                            new SearchArguments(
                                                    s.queries,
                                                    RetrievalStrategy.HYBRID,
                                                    retrieval.budgets().childBranchTopK)),
                                    "fallback-" + s.round));
            s.next = "validate";
        }
    }

    private void retrieve(Session s) {
        s.round++;
        if (s.round > limits.rounds()) throw new RunFailure("retrieval-budget-exhausted");
        s.previousEvidence =
                new HashSet<>(s.evidence.stream().map(ParentEvidence::parentChunkKey).toList());
        List<ParentEvidenceChunk> parents = List.of();
        for (var call : s.validated) {
            s.emit(
                    "tool",
                    Map.of(
                            "toolName",
                            call.call().name(),
                            "callId",
                            call.call().callId(),
                            "status",
                            "started"));
            var execution =
                    dispatcher.execute(call, s.run, s.accumulated, s.completed, s.identities);
            s.history.add(new RetrievalPlannerPort.ToolExchange(call.call(), execution.result()));
            s.emit(
                    "tool",
                    Map.of(
                            "toolName",
                            call.call().name(),
                            "callId",
                            call.call().callId(),
                            "status",
                            execution.result().status()));
            if (execution.result().status().equals("ERROR")) {
                if ("vector-unavailable".equals(execution.result().errorCode())
                        && s.round < limits.rounds()) {
                    s.decision =
                            new QualityDecision(
                                    QualityDecision.Action.RETRY,
                                    false,
                                    List.of(),
                                    List.of("vector-unavailable"),
                                    "vector-unavailable",
                                    s.queries,
                                    QualityDecision.ReturnKind.NONE);
                    s.next = "rewrite";
                    return;
                }
                throw new RunFailure(execution.result().errorCode());
            }
            parents = execution.parents();
        }
        s.evidence = assembler.assemble(parents, retrieval.budgets().parentContextCharBudget);
        s.emit("retrieve", Map.of("parentCount", s.evidence.size()));
        s.next = "quality";
    }

    private void quality(Session s) {
        s.run.authorize();
        if (s.evidence.isEmpty())
            s.decision =
                    new QualityDecision(
                            QualityDecision.Action.RETRY,
                            false,
                            List.of(),
                            List.of("missing-evidence"),
                            "no-evidence",
                            List.of(),
                            QualityDecision.ReturnKind.NONE);
        else
            try {
                s.decision = quality.analyze(s.query, s.queries, s.evidence);
                validateQuality(s);
            } catch (Exception e) {
                s.run.authorize();
                s.decision =
                        new QualityDecision(
                                QualityDecision.Action.RETRY,
                                false,
                                List.of(),
                                List.of("qa-unavailable"),
                                "qa-unavailable",
                                List.of(),
                                QualityDecision.ReturnKind.NONE);
            }
        s.emit(
                "quality",
                Map.of(
                        "action",
                        s.decision.action().name(),
                        "sufficient",
                        s.decision.sufficient(),
                        "reasonCode",
                        s.decision.reasonCode()));
        if (s.decision.action() == QualityDecision.Action.GENERATE) {
            s.next = "generate";
            return;
        }
        if (s.decision.action() == QualityDecision.Action.RETURN) {
            if (s.decision.returnKind() == QualityDecision.ReturnKind.EVIDENCE) {
                s.message =
                        s.evidence.stream()
                                .filter(
                                        e ->
                                                s.decision
                                                        .supportedEvidenceIds()
                                                        .contains(e.parentChunkKey()))
                                .map(e -> "[P" + e.firstHitOrder() + "] " + e.body())
                                .collect(java.util.stream.Collectors.joining("\n\n"));
                s.outcome = "evidence";
            } else if (s.decision.returnKind() == QualityDecision.ReturnKind.CLARIFICATION) {
                s.message = "请补充问题涉及的具体对象、版本或条件。";
                s.outcome = "clarification";
            } else s.message = "未在当前可访问知识中找到足够依据";
            s.next = "respond";
            return;
        }
        boolean newEvidence =
                s.evidence.stream().anyMatch(e -> !s.previousEvidence.contains(e.parentChunkKey()));
        int coverage = 3 - s.decision.missingAspects().size();
        if (s.round >= limits.rounds()
                || !s.run.canModel()
                || (s.round > 1 && !newEvidence && coverage <= s.coverage)) {
            s.finishInsufficient("no-progress");
            return;
        }
        s.coverage = coverage;
        s.next = "rewrite";
    }

    private void validateQuality(Session s) {
        var q = s.decision;
        registry.validate("quality-v1", ToolRegistry.json(q));
        var ids =
                s.evidence.stream()
                        .map(ParentEvidence::parentChunkKey)
                        .collect(java.util.stream.Collectors.toSet());
        if (!ids.containsAll(q.supportedEvidenceIds()))
            throw new RunFailure("invalid-quality-support");
        if (q.action() == QualityDecision.Action.GENERATE
                && (!q.sufficient()
                        || q.supportedEvidenceIds().isEmpty()
                        || q.returnKind() != QualityDecision.ReturnKind.NONE))
            throw new RunFailure("invalid-quality-decision");
        if (q.action() == QualityDecision.Action.RETRY
                && (q.sufficient() || q.returnKind() != QualityDecision.ReturnKind.NONE))
            throw new RunFailure("invalid-quality-decision");
        if (q.action() == QualityDecision.Action.RETURN
                && q.returnKind() == QualityDecision.ReturnKind.NONE)
            throw new RunFailure("invalid-quality-decision");
        if (q.returnKind() == QualityDecision.ReturnKind.EVIDENCE
                && (!q.sufficient()
                        || q.supportedEvidenceIds().isEmpty()
                        || !s.query.matches("(?s).*(原文|摘录|原始段落|original passage|quote).*")))
            throw new RunFailure("invalid-quality-decision");
    }

    private void generate(Session s) {
        s.run.authorize();
        if (!s.run.canModel()) {
            s.finishInsufficient("model-budget-exhausted");
            return;
        }
        StringBuilder prompt =
                new StringBuilder("Question: ").append(s.query).append("\nEvidence:\n");
        for (var e : s.evidence)
            prompt.append("[P")
                    .append(e.firstHitOrder())
                    .append("] ")
                    .append(e.body())
                    .append("\n");
        try {
            answer.streamAnswer(prompt.toString())
                    .doOnNext(
                            token -> {
                                s.run.check();
                                s.text.append(token);
                                s.emit("token", Map.of("text", token));
                            })
                    .blockLast(s.run.remaining());
        } catch (Exception e) {
            s.run.check();
            throw new RunFailure("answer-provider-failed");
        }
        s.run.authorize();
        s.emitCitations();
        s.outcome = "completed";
        s.done(Map.of("citationCount", s.citationCount));
        s.next = END;
    }

    private void respond(Session s) {
        s.run.authorize();
        if (!s.outcome.equals("insufficient")) {
            s.text.append(s.message);
            s.emit("token", Map.of("text", s.message));
        }
        if (s.outcome.equals("evidence")) s.emitCitations();
        else s.emit("citations", Map.of("citations", List.of()));
        s.done(
                Map.of(
                        "outcome",
                        s.outcome,
                        "noEvidence",
                        s.outcome.equals("insufficient"),
                        "message",
                        s.message));
        s.next = END;
    }

    private final class Session {
        final RunContext run;
        final CurrentUser user;
        final String query;
        final FluxSink<ChatStreamEvent> sink;
        final AtomicLong seq = new AtomicLong();
        final AtomicBoolean terminal = new AtomicBoolean(), audited = new AtomicBoolean();
        final StringBuilder text = new StringBuilder();
        final List<String> nodes = new ArrayList<>();
        final HybridRetrievalOrchestrator.Accumulation accumulated =
                new HybridRetrievalOrchestrator.Accumulation();
        final Map<String, ManualToolDispatcher.Execution> completed = new HashMap<>();
        final Map<String, String> identities = new HashMap<>();
        final List<RetrievalPlannerPort.ToolExchange> history = new ArrayList<>();
        final Set<String> attempts = new HashSet<>();
        Set<String> previousEvidence = Set.of();
        List<ParentEvidence> evidence = List.of();
        List<String> queries;
        List<ToolCall> calls = List.of();
        List<ToolRegistry.ValidatedCall> validated = List.of();
        RetrievalPlan route;
        QualityDecision decision = QualityDecision.insufficient("initial");
        String next = "route",
                outcome = "insufficient",
                message = "未在当前可访问知识中找到足够依据",
                validationError = "";
        int round, repair, coverage, citationCount;
        boolean fallback;

        Session(RunContext run, CurrentUser user, String query, FluxSink<ChatStreamEvent> sink) {
            this.run = run;
            this.user = user;
            this.query = query;
            this.queries = List.of(query);
            this.sink = sink;
        }

        synchronized void emit(String type, Map<String, Object> payload) {
            if (terminal.get() || sink.isCancelled()) return;
            var data = new LinkedHashMap<String, Object>(payload);
            data.put("round", round);
            sink.next(ChatStreamEvent.of(type, seq.incrementAndGet(), run.requestId, data));
        }

        synchronized void done(Map<String, Object> data) {
            if (terminal.get()) return;
            emit("done", data);
            terminal.set(true);
            sink.complete();
        }

        synchronized void fail(String code) {
            if (terminal.get() || sink.isCancelled()) return;
            outcome = code;
            emit("error", Map.of("error", code));
            terminal.set(true);
            sink.complete();
        }

        void finishInsufficient(String code) {
            outcome = "insufficient";
            decision = QualityDecision.insufficient(code);
            next = "respond";
        }

        void emitCitations() {
            run.authorize();
            var used = new HashSet<Integer>();
            var matcher = Pattern.compile("\\[P(\\d+)]").matcher(text);
            while (matcher.find()) used.add(Integer.parseInt(matcher.group(1)));
            var valid =
                    evidence.stream()
                            .map(ParentEvidence::firstHitOrder)
                            .collect(java.util.stream.Collectors.toSet());
            if (!valid.containsAll(used)) throw new RunFailure("answer-validation-failed");
            var citations = new ArrayList<Map<String, Object>>();
            for (var e : evidence)
                if (used.contains(e.firstHitOrder()))
                    for (var c : e.matchedChildren()) {
                        var item = new LinkedHashMap<String, Object>();
                        item.put("childChunkKey", c.chunkKey());
                        item.put("parentChunkKey", e.parentChunkKey());
                        item.put("resourceType", e.resourceType());
                        item.put("resourceId", e.resourceId());
                        item.put("revisionId", e.revisionId());
                        item.put("headingPath", c.headingPath());
                        item.put("charStart", c.charStart());
                        item.put("charEnd", c.charEnd());
                        item.put(
                                "excerpt",
                                c.content().substring(0, Math.min(120, c.content().length())));
                        citations.add(item);
                    }
            citationCount = citations.size();
            emit("citations", Map.of("citations", citations));
        }

        void audit() {
            if (audited.compareAndSet(false, true)) {
                var trace = new LinkedHashMap<String, Object>(run.stats());
                trace.put("outcome", outcome);
                trace.put("rounds", round);
                trace.put("nodes", List.copyOf(nodes));
                trace.put("toolSchemaVersion", "1");
                persistence.persistTurn(
                        run.requestId, user.id(), query, text.toString(), trace, run.requestId);
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
