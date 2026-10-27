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
import com.kwiki.rag.tool.*;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import reactor.core.publisher.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 确定性的、以 QA 质量门禁把关的知识工作流。每次知识查询都按
 * 固定阶段顺序推进：base-child → base-parent → expanded-child →
 * expanded-parent（每个阶段都会重新生成并重新评审自己的候选），
 * 只有通过门禁的候选才会被发布。阶段耗尽后会把
 * 原始问题连同完整的失败历史交给查询改写
 * Agent；最多存在 3 个查询轮次 / 2 次改写调用，内容
 * 耗尽时以固定的信息不足文案作答。规划器/工具
 * 路径不再参与主链路；其适配器仍对其他
 * 调用方可用。
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
    private final CitationDisplayNameResolver citationNames;
    private final MeterRegistry metrics;
    private final AgenticLimits limits;
    private final AgenticDebugLogger debug;
    private final double qaThreshold;
    private final Optional<Tracer> tracer;
    private final Optional<Propagator> propagator;
    private final CompiledGraph<AgentState> graph;
    private final Semaphore permits;
    /** 图增强仅在 kwiki.graph.enabled 时由装配注入；默认关闭，保持原检索路径。 */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(LangGraphAgenticWorkflow.class);
    private static final String AUTO_EXPAND_PARENTS = "kwiki.agentic.auto-expand-parents";
    private volatile com.kwiki.rag.retrieval.GraphRetrievalEnhancer graphEnhancer;
    /** 直答模型端口；缺失时 direct 节点直接转入检索。 */
    private volatile DirectAnswerPort directAnswer;
    /** planner 三件套齐全且开关打开时，QA 走 plan-execute；否则保持固定阶梯。 */
    private volatile QaPlanningService planning;
    private volatile AgentToolDispatcher toolDispatcher;
    private volatile boolean plannerEnabled;
    /** 评审 relevance 不高于该值，才开放 answer_directly / 触发固定阶梯的 *_PARENT 跳过；默认值写在代码里，由 Nacos 覆盖。 */
    private volatile double rerouteRelevanceThreshold = 0.2;
    /**
     * 生成前自动补父块：子块命中后先取去重父块再生成，而不是先只用子块生成、评审不过才补。
     * 支持热更新：Nacos 推送后重新读取；每次 run 开始时取一次快照，run 内不变。
     */
    private volatile boolean autoExpandParents = true;
    private volatile Environment environment;
    /** 质量评审节点时限：评审可开启思考（用途分配），默认 120s。 */
    private volatile java.time.Duration qualityDeadline = java.time.Duration.ofSeconds(120);
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
            CitationDisplayNameResolver citationNames,
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
        this.citationNames = citationNames;
        this.metrics = metrics;
        this.limits = limits;
        this.debug = debug;
        this.qaThreshold = qaThreshold;
        this.tracer = tracer;
        this.propagator = propagator;        permits = new Semaphore(limits.concurrentRuns());
        var g = new StateGraph<AgentState>(AgentState::new);
        Map<String, Consumer<Session>> nodes = new LinkedHashMap<>();
        nodes.put("route", this::route);
        nodes.put("plan", this::plan);
        nodes.put("execute", this::execute);
        nodes.put("retrieve", this::retrieve);
        nodes.put("direct", this::direct);
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
                        long nodeStartedAt = s.nowMs();
                        s.debugStage(entry.getKey(), "start", 0, () -> Map.of(
                                "query", AgenticDebugLogger.boundedQuery(s.currentQuery),
                                "next", s.next));
                        try (var binding = s.run.bind();
                                var limit = s.run.limit(nodeDeadline(name))) {
                            s.run.step();
                            s.nodes.add(name);
                            entry.getValue().accept(s);
                            s.debugStage(entry.getKey(), "node-complete", s.sinceMs(nodeStartedAt),
                                    () -> Map.of("next", s.next));
                            return CompletableFuture.completedFuture(
                                    Map.of("next", s.next, "round", s.queryRound));
                        } catch (Exception e) {
                            String errorCode = code(e);
                            if (AgenticErrorCodes.CANCELLED.equals(errorCode)) {
                                // 取消不是故障：正常结束图（避免 langgraph4j 按 ERROR 记录），
                                // graph.invoke 返回后再以 cancelled 终态通知调用方
                                s.cancelledInNode = true;
                                s.debugStage(entry.getKey(), "cancelled", s.sinceMs(nodeStartedAt), Map::of);
                                log.info("agentic run cancelled requestId={} node={}", s.run.requestId, name);
                                return CompletableFuture.completedFuture(Map.of("next", END, "round", s.queryRound));
                            }
                            s.debugStage(entry.getKey(), "failed", s.sinceMs(nodeStartedAt),
                                    () -> Map.of("errorCode", errorCode));
                            return CompletableFuture.failedFuture(e);
                        }
                    });
            g.addConditionalEdges(
                    name,
                    state -> CompletableFuture.completedFuture((String) state.data().get("next")),
                    destinations);
        }
        g.addEdge(START, "route");
        // LangGraph4j 1.8.20 使用编译期递归上限。RunContext
        // 仍是应用层权威的步骤预算。
        graph = g.compile(CompileConfig.builder().recursionLimit(limits.steps() + 2).build());
    }

    private java.time.Duration nodeDeadline(String name) {
        return switch (name) {
            case "generate" -> java.time.Duration.ofSeconds(120);
            case "quality" -> qualityDeadline;
            case "route" -> java.time.Duration.ofSeconds(10);
            case "plan" -> limits.modelDeadline().multipliedBy(2).plusSeconds(15);
            case "execute" -> java.time.Duration.ofSeconds(60);
            default -> java.time.Duration.ofSeconds(30);
        };
    }

    /** 图功能启用时由 Spring 装配注入；不存在时增强整体跳过。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureGraphEnhancement(
            ObjectProvider<com.kwiki.rag.retrieval.GraphRetrievalEnhancer> enhancer) {
        this.graphEnhancer = enhancer.getIfAvailable();
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureDirectAnswer(ObjectProvider<DirectAnswerPort> provider) {
        this.directAnswer = provider.getIfAvailable();
    }

    /** 测试与手工装配用的注入入口。 */
    public void useDirectAnswer(DirectAnswerPort port) {
        this.directAnswer = port;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configurePlanner(ObjectProvider<QaPlanningService> planning,
                          ObjectProvider<AgentToolDispatcher> dispatcher,
                          @Value("${kwiki.agentic.planner-enabled:false}") boolean enabled) {
        this.planning = planning.getIfAvailable();
        this.toolDispatcher = dispatcher.getIfAvailable();
        this.plannerEnabled = enabled;
    }

    /** 测试与手工装配用的注入入口。 */
    public void usePlanner(QaPlanningService planning, AgentToolDispatcher dispatcher, boolean enabled) {
        this.planning = planning;
        this.toolDispatcher = dispatcher;
        this.plannerEnabled = enabled;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureReroute(@Value("${kwiki.agentic.reroute-relevance-threshold:0.2}") double threshold) {
        this.rerouteRelevanceThreshold = threshold;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureQualityDeadline(@Value("${kwiki.agentic.quality-deadline:120s}") java.time.Duration deadline) {
        if (deadline != null && !deadline.isNegative() && !deadline.isZero()) this.qualityDeadline = deadline;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureAutoExpandParents(Environment environment) {
        this.environment = environment;
        reloadAutoExpandParents();
    }

    /** 配置中心推送后重新读取；值无法解析时保留原值并记 WARN。 */
    @EventListener(EnvironmentChangeEvent.class)
    public void onEnvironmentChange() {
        reloadAutoExpandParents();
    }

    private void reloadAutoExpandParents() {
        Environment env = environment;
        if (env == null) {
            return;
        }
        try {
            boolean next = env.getProperty(AUTO_EXPAND_PARENTS, Boolean.class, true);
            if (next != autoExpandParents) {
                autoExpandParents = next;
                log.info("{} refreshed to {}", AUTO_EXPAND_PARENTS, next);
            }
        } catch (RuntimeException e) {
            log.warn("{} refresh rejected, keeping {}: {}", AUTO_EXPAND_PARENTS, autoExpandParents, e.getMessage());
        }
    }

    /** 测试与手工装配用的注入入口。 */
    public void useAutoExpandParents(boolean enabled) {
        this.autoExpandParents = enabled;
    }

    /** 测试与手工装配用的注入入口。 */
    public void useRerouteThreshold(double threshold) {
        this.rerouteRelevanceThreshold = threshold;
    }

    private boolean plannerActive(Session s) {
        return plannerEnabled && planning != null && toolDispatcher != null && !s.plannerDegraded;
    }

    /** 进入检索的入口：planner 启用时先规划，否则走固定阶梯的 retrieve。 */
    private String retrievalEntry(Session s) {
        if (plannerActive(s)) {
            if (s.pool == null) s.pool = new EvidencePool(s.currentQuery);
            return "plan";
        }
        return "retrieve";
    }

    @Override
    public Flux<ChatStreamEvent> answer(CurrentUser user, String query) {
        return answer(user, query, List.of());
    }

    @Override
    public Flux<ChatStreamEvent> answer(CurrentUser user, String query, List<ChatTurn> conversationHistory) {
        return streamAnswer(user, query, conversationHistory, true, Set.of(), Set.of(), RouteInput.NONE, RunObserver.NOOP);
    }

    @Override
    public Flux<ChatStreamEvent> answerInSession(CurrentUser user, String query, List<ChatTurn> conversationHistory) {
        return streamAnswer(user, query, conversationHistory, false, Set.of(), Set.of(), RouteInput.NONE, RunObserver.NOOP);
    }

    @Override
    public Flux<ChatStreamEvent> answerInSession(CurrentUser user, String query, List<ChatTurn> conversationHistory,
                                                  Set<Long> kbIds, Set<Long> pageIds) {
        return streamAnswer(user, query, conversationHistory, false, kbIds, pageIds, RouteInput.NONE, RunObserver.NOOP);
    }

    @Override
    public Flux<ChatStreamEvent> answerInSession(CurrentUser user, String query, List<ChatTurn> conversationHistory,
                                                  Set<Long> kbIds, Set<Long> pageIds, RouteInput routeInput) {
        return streamAnswer(user, query, conversationHistory, false, kbIds, pageIds,
                routeInput == null ? RouteInput.NONE : routeInput, RunObserver.NOOP);
    }

    @Override
    public Flux<ChatStreamEvent> answerInSession(CurrentUser user, String query, List<ChatTurn> conversationHistory,
                                                  Set<Long> kbIds, Set<Long> pageIds, RouteInput routeInput,
                                                  RunObserver observer) {
        return streamAnswer(user, query, conversationHistory, false, kbIds, pageIds,
                routeInput == null ? RouteInput.NONE : routeInput, observer);
    }

    private Flux<ChatStreamEvent> streamAnswer(CurrentUser user, String query, List<ChatTurn> conversationHistory, boolean persistConversation,
                                                Set<Long> kbIds, Set<Long> pageIds, RouteInput routeInput, RunObserver observer) {
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
                                            debug.terminal(null, AgenticErrorCodes.AGENT_BUSY,
                                                    AgenticErrorCodes.AGENT_BUSY, () -> Map.of(
                                                            "requestId", requestId,
                                                            "query", AgenticDebugLogger.boundedQuery(query)));
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
                                                                run.observer(observer);
                                                                run.usageSource(observer.usageSource());
                                                                s =
                                                                        new Session(
                                                                                run, user, query,
                                                                                conversationHistory, sink, persistConversation,
                                                                                kbIds, pageIds);
                                                                s.routeInput = routeInput;
                                                                s.autoExpandParents = autoExpandParents;
                                                                reference.set(s);
                                                                s.debugStage("workflow", "start", 0, () -> Map.of(
                                                                        "query", AgenticDebugLogger.boundedQuery(query),
                                                                        "conversationTurns", conversationHistory == null
                                                                                ? 0 : conversationHistory.size(),
                                                                        "knowledgeBaseScopeCount", kbIds.size(),
                                                                        "pageScopeCount", pageIds.size()));
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
                                                                if (s.cancelledInNode)
                                                                    s.fail(AgenticErrorCodes.CANCELLED);
                                                            } catch (Exception e) {
                                                                if (s != null) s.fail(code(e));
                                                                else if (!sink.isCancelled()) {
                                                                    debug.terminal(null,
                                                                            AgenticErrorCodes.INTERNAL_ERROR,
                                                                            AgenticErrorCodes.INTERNAL_ERROR,
                                                                            () -> Map.of(
                                                                                    "requestId", requestId,
                                                                                    "query", AgenticDebugLogger.boundedQuery(query)));
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
                                                                            && !s.terminal.get()) {
                                                                        s.outcome = AgenticErrorCodes.CANCELLED;
                                                                        Session cancelledSession = s;
                                                                        s.debugTerminal(
                                                                                AgenticErrorCodes.CANCELLED,
                                                                                AgenticErrorCodes.CANCELLED,
                                                                                () -> Map.of(
                                                                                        "queryRound", cancelledSession.queryRound,
                                                                                        "stage", cancelledSession.stage.name(),
                                                                                        "nodes", List.copyOf(cancelledSession.nodes)));
                                                                    }
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
    // 节点
    // ------------------------------------------------------------------

    private void route(Session s) {
        long startedAt = s.nowMs();
        s.emitActivity(ActivityEvent.started(stepId(s, "route"), null, s.queryRound, null,
                ActivityEvent.Phase.ROUTE, startedAt, "正在理解问题"));
        String normalized = QueryNormalizer.normalize(s.query);
        if (normalized.isBlank() || normalized.length() > 1000)
            throw new RunFailure(AgenticErrorCodes.INVALID_QUERY);
        s.currentQuery = normalized;
        s.route = router.route(s.query, s.routeContext());
        s.debugStage("route", "complete", s.sinceMs(startedAt), () -> {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("query", AgenticDebugLogger.boundedQuery(s.currentQuery));
            fields.put("intent", s.route.intent().name());
            fields.put("source", s.route.source().name());
            fields.put("needsRetrieval", s.route.needsRetrieval());
            fields.put("needsClarification", s.route.needsClarification());
            fields.put("rewriteMode", s.route.rewriteMode().name());
            fields.put("confidence", s.route.confidence());
            fields.put("matchedRuleIds", s.route.matchedRuleIds());
            fields.put("priorRoute", s.routeInput.prior() == null ? "none" : s.routeInput.prior().toMap());
            fields.put("clarificationResumed", s.routeInput.clarificationResumed());
            if (s.route.fallbackReason() != null) {
                fields.put("fallbackReason", s.route.fallbackReason());
            }
            return fields;
        });
        if (s.route.needsClarification() && !s.routeInput.clarificationResumed()) {
            clarify(s, startedAt);
            return;
        }
        if (!s.route.needsRetrieval()) {
            if (isGreeting(s.route)) {
                s.message = "你好，我可以根据你有权访问的 Wiki 内容检索资料并回答问题。";
                s.outcome = "direct";
                s.next = "respond";
                s.emitActivity(ActivityEvent.finished(stepId(s, "route"), null, s.queryRound, null,
                        ActivityEvent.Phase.ROUTE, ActivityEvent.Status.COMPLETED, startedAt,
                        s.sinceMs(startedAt), "问候直接回复", Map.of(), null));
                return;
            }
            s.next = "direct";
            s.emitActivity(ActivityEvent.finished(stepId(s, "route"), null, s.queryRound, null,
                    ActivityEvent.Phase.ROUTE, ActivityEvent.Status.COMPLETED, startedAt,
                    s.sinceMs(startedAt), "无需检索，直接回答", Map.of(), null));
            return;
        }
        s.emitActivity(ActivityEvent.finished(stepId(s, "route"), null, s.queryRound, null,
                ActivityEvent.Phase.ROUTE, ActivityEvent.Status.COMPLETED, startedAt,
                s.sinceMs(startedAt), "已识别为知识检索", Map.of(), null));
        // 第 1 轮原样使用归一化后的原始查询：首次检索
        // 之前不做语义改写。
        s.next = retrievalEntry(s);
    }

    private static boolean isGreeting(RetrievalPlan plan) {
        return plan.source() == RouteSource.RULE
                && plan.intent() == Intent.DIRECT_ANSWER
                && plan.matchedRuleIds().stream().anyMatch(id -> id.startsWith("greet-"));
    }

    /** 路由阶段的反问。 */
    private void clarify(Session s, long startedAt) {
        emitClarification(s, s.route.clarify(), startedAt, "route", ActivityEvent.Phase.ROUTE);
    }

    /**
     * 反问：发出 clarification 事件后以 outcome=clarification 结束本次 run，由下次请求携带回答续跑。
     * 路由与 planner（ask_user）共用。
     */
    private void emitClarification(Session s, ClarifyRequest request, long startedAt, String stepKey,
                                   ActivityEvent.Phase phase) {
        String clarificationId = UUID.randomUUID().toString();
        s.emit("clarification", request.toPayload(clarificationId));
        s.outcome = "clarification";
        s.message = request.question();
        s.debugStage(stepKey, "clarification", s.sinceMs(startedAt), () -> Map.of(
                "clarificationId", clarificationId,
                "options", request.options().size(),
                "allowFreeText", request.allowFreeText(),
                "source", request.source()));
        s.emitActivity(ActivityEvent.finished(stepId(s, stepKey), null, s.queryRound, null,
                phase, ActivityEvent.Status.COMPLETED, startedAt,
                s.sinceMs(startedAt), "需要你确认", Map.of(), null));
        s.debugTerminal("clarification", null, () -> Map.of(
                "queryRound", s.queryRound,
                "stage", s.stage.name()));
        s.done(Map.of(
                "outcome", "clarification",
                "clarificationId", clarificationId,
                "route", s.routeState()));
        s.next = END;
    }

    /** 无需检索时的直答；模型输出哨兵或不可用则转入检索（每个请求只会发生一次）。 */
    private void direct(Session s) {
        long startedAt = s.nowMs();
        String step = stepId(s, "direct");
        s.emitActivity(ActivityEvent.started(step, null, s.queryRound, null,
                ActivityEvent.Phase.GENERATION, startedAt, "正在直接回答"));
        if (directAnswer == null) {
            escalate(s, step, startedAt, "direct-unavailable");
            return;
        }
        s.run.authorize();
        String text;
        try {
            text = directAnswer.answer(s.query, s.conversationHistory);
        } catch (RunFailure e) {
            throw e;
        } catch (Exception e) {
            s.run.check();
            escalate(s, step, startedAt, "direct-failed");
            return;
        }
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || trimmed.startsWith(DirectAnswerPort.ESCALATE_SENTINEL)) {
            escalate(s, step, startedAt, "direct-escalated");
            return;
        }
        s.message = trimmed;
        s.outcome = "direct";
        s.next = "respond";
        s.debugStage("direct", "complete", s.sinceMs(startedAt), () -> Map.of("chars", trimmed.length()));
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, null,
                ActivityEvent.Phase.GENERATION, ActivityEvent.Status.COMPLETED, startedAt,
                s.sinceMs(startedAt), "已直接回答", Map.of("chars", trimmed.length()), null));
    }

    private void escalate(Session s, String step, long startedAt, String reason) {
        if (s.rerouted) {
            // planner 刚判定「与知识库无关」：不能再回检索，避免来回跳；以信息不足结束
            String code = "direct-after-reroute-failed";
            s.debugStage("direct", "reroute-failed", s.sinceMs(startedAt), () -> Map.of("reason", reason));
            s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, null,
                    ActivityEvent.Phase.GENERATION, ActivityEvent.Status.SKIPPED, startedAt,
                    s.sinceMs(startedAt), "无法直接回答", Map.of(), code));
            s.finishInsufficient(code);
            return;
        }
        s.route = RetrievalPlan.fallback(reason);
        s.debugStage("direct", "escalated", s.sinceMs(startedAt), () -> Map.of("reason", reason));
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, null,
                ActivityEvent.Phase.GENERATION, ActivityEvent.Status.SKIPPED, startedAt,
                s.sinceMs(startedAt), "需要查询知识库，转入检索", Map.of(), reason));
        s.next = retrievalEntry(s);
    }

    /** 执行当前阶段的检索部分（子分块或父分块）。 */
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
        s.run.observe(() -> new RunObserver.ChildrenObserved(s.queryRound, s.stage.name(),
                s.children.stream().map(ChildEvidence::chunkKey).toList(), false));
        s.parentEvidence = List.of();
        applyGraphEnhancement(s, budget);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("branchTopK", budget.branchTopK());
        metrics.put("finalTopK", budget.finalTopK());
        metrics.put("fusedCandidateCount", outcome.fusedCandidateCount());
        metrics.put("retainedChildCount", s.children.size());
        metrics.put("sources", activitySources(s.children));
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
                        "degradations", s.lastDegradations,
                        "results", s.children.stream().map(child -> {
                            var result = new LinkedHashMap<String, Object>();
                            result.put("chunkKey", child.chunkKey());
                            result.put("parentChunkKey", child.parentChunkKey());
                            result.put("bm25Rank", child.bm25Rank());
                            result.put("vectorRank", child.vectorRank());
                            result.put("rrfScore", child.rrfScore());
                            return result;
                        }).toList()));
        if (s.children.isEmpty()) {
            // 成功但命中为零：绝不让生成模型空转。
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
        if (s.autoExpandParents) {
            expandParentsBeforeGeneration(s);
        }
        s.next = "generate";
    }

    /** 前端检索时间线用来渲染 chunk 标签的来源列表；固定阶梯与 planner 的检索工具共用。 */
    private static List<Map<String, Object>> activitySources(List<ChildEvidence> children) {
        return children.stream().map(child -> {
            var source = new LinkedHashMap<String, Object>();
            source.put("childChunkKey", child.chunkKey());
            source.put("parentChunkKey", child.parentChunkKey());
            source.put("kbId", child.kbId());
            source.put("resourceId", child.resourceId());
            source.put("resourceType", child.resourceType());
            source.put("revisionId", child.revisionId());
            source.put("headingPath", child.headingPath());
            source.put("charStart", child.charStart());
            source.put("charEnd", child.charEnd());
            source.put("excerpt", child.content());
            return (Map<String, Object>) source;
        }).toList();
    }

    /** 检索类工具完成后，把本次新增的 chunk 作为 sources 附到活动事件上，与固定阶梯的展示一致。 */
    private Map<String, Object> toolActivityMetrics(Session s, String tool, ToolResult result) {
        if (!"SUCCESS".equals(result.status())
                || !(AgentTools.SEARCH_CHUNKS.equals(tool) || AgentTools.GRAPH_EXPAND.equals(tool))) {
            return result.stats();
        }
        List<ChildEvidence> added = result.evidenceRefs().stream()
                .map(s.pool::child).flatMap(Optional::stream).toList();
        if (added.isEmpty()) {
            return result.stats();
        }
        Map<String, Object> metrics = new LinkedHashMap<>(result.stats());
        metrics.put("sources", activitySources(added));
        return metrics;
    }

    /**
     * retrieveChildren 阶段的确定性图增强：图禁用（未注入）或无 Chunk 命中时
     * 是无操作；图分支失败保留 Chunk 证据继续原流程。所有轮共享 run 级
     * 增强预算，DIRECT 路径不查图。
     */
    private void applyGraphEnhancement(Session s, QaRetrievalBudgets.StageBudget budget) {
        if (graphEnhancer == null || s.children.isEmpty()) {
            return;
        }
        boolean direct = s.route != null && !s.route.needsRetrieval();
        var selection = graphSelection(s);
        var enhancement = graphEnhancer.enhance(
                s.run.scope.userId(), s.run.scope.superuser(),
                s.children, s.currentQuery, selection, s.embeddingCache,
                s.graphBudget, direct);
        if (enhancement.enhanced()) {
            s.children = trimToBudget(enhancement.mergedChildren(), budget.childCharBudget());
            s.run.observe(() -> new RunObserver.ChildrenObserved(s.queryRound, s.stage.name(),
                    s.children.stream().map(ChildEvidence::chunkKey).toList(), true));
        }
        if (!enhancement.degradations().isEmpty()) {
            var combined = new ArrayList<>(s.lastDegradations);
            combined.addAll(enhancement.degradations());
            s.lastDegradations = List.copyOf(combined);
        }
        if (enhancement.enhanced() || !enhancement.degradations().isEmpty()) {
            s.debugStage("retrieval", "graph-enhancement", 0, () -> Map.of(
                    "enhanced", enhancement.enhanced(),
                    "communities", enhancement.communitiesUsed().size(),
                    "graphChildrenAdded", s.graphBudget.childrenAdded(),
                    "graphEdgeChecks", s.graphBudget.edgeChecks(),
                    "graphElapsedMs", s.graphBudget.usedMs()));
        }
    }

    private void retrieveParents(Session s) {
        var outcome = fetchStageParents(s, s.usedParentKeys);
        if (outcome.skipped()) {
            s.recordStageFailure(outcome.reason());
            advanceRecovery(s);
            return;
        }
        s.parentEvidence = withUncoveredChildren(s, outcome.evidence(), s.children);
        s.usedParentKeys.addAll(QaParentFetchService.keysOf(outcome.evidence()));
        s.next = "generate";
    }

    /**
     * 生成前自动补父块（固定阶梯）：子块检索后直接进入对应的 *_PARENT 阶段，用去重父块生成，
     * 不再先只拿子块生成。父块全部已在本轮上下文里也照常提供（扩大阶段的父块多与基础阶段重叠）；
     * 取不到父块或父块获取预算用尽时，子块以截断父块的形式进入上下文，阶段照样推进。
     */
    private void expandParentsBeforeGeneration(Session s) {
        s.stage = s.stage.next();
        List<ParentEvidence> parents = List.of();
        if (s.parentFetchUsed < limits.parentFetches()) {
            var outcome = fetchStageParents(s, Set.of());
            parents = outcome.evidence();
        }
        s.parentEvidence = QaParentFetchService.withUncoveredChildren(parents, s.children);
        s.usedParentKeys.addAll(QaParentFetchService.keysOf(parents));
    }

    /** 取当前阶段子块的父块并上报活动；跳过时 evidence 为空。 */
    private QaParentFetchService.ParentStageOutcome fetchStageParents(Session s, Set<String> usedParentKeys) {
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
                retrievalBudgets.parentCharBudget(), usedParentKeys);
        if (outcome.skipped()) {
            s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, s.stage.name(),
                    ActivityEvent.Phase.PARENT_FETCH, ActivityEvent.Status.SKIPPED, startedAt,
                    s.sinceMs(startedAt), "无新的完整上下文", Map.of(), outcome.reason()));
            s.debugStage("parent-fetch", "skipped", outcome.elapsedMs(), () -> Map.of(
                    "reason", outcome.reason()));
            return outcome;
        }
        s.debugStage("parent-fetch", "complete", outcome.elapsedMs(), () -> Map.of(
                "parentCount", outcome.parentCount()));
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, s.stage.name(),
                ActivityEvent.Phase.PARENT_FETCH, ActivityEvent.Status.COMPLETED, startedAt,
                s.sinceMs(startedAt), "补充 " + outcome.parentCount() + " 个完整段落",
                Map.of("parentCount", outcome.parentCount()), null));
        return outcome;
    }

    /** 自动补父块开启时，把没被父块覆盖的子块补进父块上下文；关闭时保持只用父块。 */
    private static List<ParentEvidence> withUncoveredChildren(Session s, List<ParentEvidence> parents,
                                                              List<ChildEvidence> children) {
        return s.autoExpandParents ? QaParentFetchService.withUncoveredChildren(parents, children) : parents;
    }

    /**
     * 生成前自动补父块（planner）：证据池里尚无父块的子块先取父块再生成，
     * planner 不必为补上下文单独规划一轮 expand_parents。
     */
    private void expandPoolParents(Session s) {
        Set<String> used = s.pool.usedParentKeys();
        List<ChildEvidence> uncovered = s.pool.children().stream()
                .filter(child -> !used.contains(child.parentChunkKey()))
                .toList();
        if (uncovered.isEmpty() || s.pool.parentFetches() >= limits.parentFetches()) {
            return;
        }
        s.run.authorize();
        s.pool.useParentFetch();
        String step = stepId(s, "parent");
        long startedAt = s.nowMs();
        s.emitActivity(ActivityEvent.started(step, null, s.queryRound, null,
                ActivityEvent.Phase.PARENT_FETCH, startedAt, "正在补充完整上下文"));
        var outcome = parentFetch.fetchParents(s.run.scope, s.run, uncovered,
                retrievalBudgets.base().parentLimit(), retrievalBudgets.parentCharBudget(), used);
        int added = outcome.skipped() ? 0 : s.pool.addParents(outcome.evidence());
        s.debugStage("parent-fetch", outcome.skipped() ? "skipped" : "complete", outcome.elapsedMs(),
                () -> outcome.skipped()
                        ? Map.<String, Object>of("reason", outcome.reason())
                        : Map.<String, Object>of("parentCount", added));
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, null,
                ActivityEvent.Phase.PARENT_FETCH,
                outcome.skipped() ? ActivityEvent.Status.SKIPPED : ActivityEvent.Status.COMPLETED,
                startedAt, s.sinceMs(startedAt),
                outcome.skipped() ? "无新的完整上下文" : "补充 " + added + " 个完整段落",
                Map.of("parentCount", added), outcome.reason()));
    }

    private void generate(Session s) {
        s.generationsUsed++;
        if (s.generationsUsed > limits.generations()) {
            throw new RunFailure("generation-budget-exhausted");
        }
        if (s.plannerMode()) {
            s.children = s.pool.children();
            s.parentEvidence = withUncoveredChildren(s, s.pool.numberedParents(), s.children);
            s.currentQuery = s.pool.currentQuery();
            s.stage = s.parentEvidence.isEmpty() ? AttemptStage.BASE_CHILD : AttemptStage.BASE_PARENT;
            s.generatedPoolVersion = s.pool.version();
        }
        String step = stepId(s, "generate");
        long startedAt = s.nowMs();
        s.emitActivity(ActivityEvent.started(step, null, s.queryRound, s.stage.name(),
                ActivityEvent.Phase.GENERATION, startedAt, "正在生成候选回答"));
        String context = s.stage.isParentStage()
                ? parentContext(s.parentEvidence)
                : childContext(s.children);
        s.run.observe(() -> new RunObserver.ContextObserved(s.queryRound, s.stage.name(), s.currentQuery, context,
                contextSegments(s.stage.isParentStage(), s.children, s.parentEvidence)));
        CandidateGenerator.Generated generated;
        s.run.thinkingListener(text -> s.emit("reasoning-summary", Map.of("stepId", step, "text", text)));
        try {
            generated = candidates.generate(
                    s.run, s.query, s.currentQuery, s.stage, s.children, s.parentEvidence, context);
        } finally {
            s.run.thinkingListener(null);
        }
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
        s.emitActivity(ActivityEvent.started(step, null, s.queryRound, s.stage.name(),
                ActivityEvent.Phase.QUALITY, startedAt, "正在质量评审"));
        var input = new QualityV2Input(s.query, s.currentQuery, s.candidate, List.of());
        QualityAssessment assessment;
        s.run.thinkingListener(text -> s.emit("reasoning-summary", Map.of("stepId", step, "text", text)));
        try {
            assessment = quality.assess(input);
        } catch (RunFailure e) {
            s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, s.stage.name(),
                    ActivityEvent.Phase.QUALITY, ActivityEvent.Status.FAILED, startedAt,
                    s.sinceMs(startedAt), "质量评审暂不可用", Map.of(), e.getMessage()));
            throw e;
        } finally {
            s.run.thinkingListener(null);
        }
        var evidenceIds = evidenceIdsOf(s.candidate);
        boolean passes = assessment.gatePasses(qaThreshold, evidenceIds);
        // 固定阶梯：证据与问题无关时跳过 *_PARENT；日志与行为共用同一个布尔值，避免分叉
        boolean skipParents = !passes && !s.plannerMode() && assessment.relevance() <= rerouteRelevanceThreshold;
        s.debugStage("quality", passes ? "pass" : "fail", 0, () -> Map.of(
                "candidateId", s.candidate.candidateId(),
                "threshold", qaThreshold,
                "relevance", assessment.relevance(),
                "coverage", assessment.coverage(),
                "faithfulness", assessment.faithfulness(),
                "reasonCode", assessment.reasonCode(),
                "missingAspects", assessment.missingAspects(),
                "unsupportedClaims", assessment.unsupportedClaims(),
                "skipParentStages", skipParents));
        if (passes) {
            s.assessment = assessment;
            s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, s.stage.name(),
                    ActivityEvent.Phase.QUALITY, ActivityEvent.Status.COMPLETED, startedAt,
                    s.sinceMs(startedAt), "质量评审通过",
                    Map.of("supportedEvidenceCount", assessment.supportedEvidenceIds().size(),
                            "relevance", assessment.relevance(),
                            "coverage", assessment.coverage(),
                            "faithfulness", assessment.faithfulness()),
                    null));
            publish(s);
            return;
        }
        s.recordStageFailure(assessment.reasonCode());
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, s.stage.name(),
                ActivityEvent.Phase.QUALITY, ActivityEvent.Status.COMPLETED, startedAt,
                    s.sinceMs(startedAt), "质量评审未通过：" + businessReason(assessment),
                    Map.of("reasonCode", assessment.reasonCode(),
                            "relevance", assessment.relevance(),
                            "coverage", assessment.coverage(),
                            "faithfulness", assessment.faithfulness()),
                    assessment.reasonCode()));
        if (s.plannerMode()) {
            s.lastReview = assessment;
            s.pool.recordFailure(assessment.reasonCode());
            s.next = "plan";
        } else {
            // 证据与问题无关时，无关块的父段落同样无关：跳过 *_PARENT 阶段，直接扩大检索或改写
            s.skipParentStages = skipParents;
            advanceRecovery(s);
        }
    }

    /** 只发布被评审过的候选文本；不做二次生成。 */
    private void publish(Session s) {
        // 输出边界：在任何字节离开服务端之前，
        // 重新校验作用域与证据的有效性。
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
                "stage", s.stage.name(),
                "answerChars", text.length(),
                "citationCount", s.citationCount));
        s.done(Map.of(
                "outcome", s.outcome,
                "citationCount", s.citationCount,
                "queryRound", s.queryRound,
                "attemptStage", s.stage.name(),
                "route", s.routeState()));
        s.next = END;
    }

    private com.kwiki.graph.GraphSelectionScope graphSelection(Session s) {
        return s.selectedPageIds.isEmpty()
                ? com.kwiki.graph.GraphSelectionScope.all()
                : com.kwiki.graph.GraphSelectionScope.of(s.selectedPageIds.stream()
                        .map(com.kwiki.graph.GraphResourceId::page)
                        .toArray(com.kwiki.graph.GraphResourceId[]::new));
    }

    /** 规划：planner 只给出工具策略，校验通过的调用交给 execute 由代码执行。 */
    private void plan(Session s) {
        long startedAt = s.nowMs();
        // 本轮检索后回来决定是否补父块：同一轮内续规划，不占查询轮次
        boolean continuing = s.continuePlanRound;
        s.continuePlanRound = false;
        if (continuing && (!s.run.canModel(2) || !s.run.canTools(1))) {
            s.next = "generate"; // 预算只够生成：本轮证据直接用于生成
            return;
        }
        if ((!continuing && s.planRound >= limits.queryRounds()) || !s.run.canModel() || !s.run.canTools(1)) {
            s.finishInsufficient("recovery-exhausted");
            return;
        }
        if (!continuing) {
            s.planRound++;
            s.queryRound = s.planRound;
            s.roundToolCount = 0;
            s.roundReplanned = false;
        }
        String step = stepId(s, continuing ? "replan" : "plan");
        s.emitActivity(ActivityEvent.started(step, null, s.queryRound, null,
                ActivityEvent.Phase.PLAN, startedAt, "正在规划检索策略"));
        var decision = planning.plan(new QaPlanningService.Input(
                s.query, s.pool, s.route, s.conversationHistory, s.lastReview,
                s.planRound, limits.queryRounds(), graphEnhancer != null,
                !s.routeInput.clarificationResumed(),
                s.pool.version() != s.generatedPoolVersion,
                List.copyOf(s.toolHistory), s.run, canReroute(s)));
        switch (decision) {
            case QaPlanningService.Decision.Calls calls -> {
                s.pendingCalls = calls.calls();
                List<String> names = calls.calls().stream().map(c -> c.call().name()).toList();
                s.plannedTools.addAll(names);
                s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, null,
                        ActivityEvent.Phase.PLAN, ActivityEvent.Status.COMPLETED, startedAt,
                        s.sinceMs(startedAt), "已规划：" + String.join("、", names),
                        Map.of("toolCount", names.size()), null));
                s.next = "execute";
            }
            case QaPlanningService.Decision.Sufficient sufficient -> {
                s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, null,
                        ActivityEvent.Phase.PLAN, ActivityEvent.Status.COMPLETED, startedAt,
                        s.sinceMs(startedAt), "现有证据足够", Map.of(), null));
                s.next = "generate";
            }
            case QaPlanningService.Decision.Degrade degrade -> degrade(s, step, startedAt, degrade.reason());
            case QaPlanningService.Decision.Reroute reroute -> reroute(s, step, startedAt, reroute.reason());
        }
    }

    /** 允许 planner 改道：已检索过、上一轮评审判定证据无关，且不是反问续跑。 */
    private boolean canReroute(Session s) {
        return !s.routeInput.clarificationResumed()
                && s.pool != null && s.pool.hybridRetrievals() > 0
                && s.pool.version() == s.generatedPoolVersion
                && s.lastReview != null && s.lastReview.relevance() <= rerouteRelevanceThreshold;
    }

    /** planner 判定问题与知识库无关：路由改为直答并交给 direct 节点；之后 direct 不能再升级回检索。 */
    private void reroute(Session s, String step, long startedAt, String reason) {
        if (!s.run.canModel()) {
            // 直答需要一次模型调用，而预算可能恰好在规划后耗尽
            s.finishInsufficient("recovery-exhausted");
            return;
        }
        String from = s.route == null ? "none" : s.route.intent().name();
        double relevance = s.lastReview == null ? 0.0 : s.lastReview.relevance();
        s.route = RetrievalPlan.planner(Intent.DIRECT_ANSWER, false);
        s.rerouted = true;
        s.debugStage("plan", "reroute", s.sinceMs(startedAt), () -> Map.of(
                "reason", AgenticDebugLogger.boundedQuery(reason),
                "relevance", relevance,
                "fromIntent", from,
                "toIntent", Intent.DIRECT_ANSWER.name(),
                "planRound", s.planRound));
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, null,
                ActivityEvent.Phase.PLAN, ActivityEvent.Status.COMPLETED, startedAt,
                s.sinceMs(startedAt), "与知识库无关，改为直接回答", Map.of(), null));
        s.next = "direct";
    }

    /** 规划不可用：降级回固定阶梯，预算计数从证据池带过去；降级只发生一次。 */
    private void degrade(Session s, String step, long startedAt, String reason) {
        s.plannerDegraded = true;
        s.hybridRetrievalUsed = s.pool.hybridRetrievals();
        s.parentFetchUsed = s.pool.parentFetches();
        s.rewritesUsed = s.pool.rewritesUsed();
        s.currentQuery = s.pool.currentQuery();
        s.rewriteHistory.addAll(s.pool.rewriteHistory());
        s.children = List.of();
        s.parentEvidence = List.of();
        s.usedParentKeys.clear();
        s.stage = AttemptStage.BASE_CHILD;
        metrics.counter("kwiki_planner_degraded_total", "reason",
                reason.contains(":") ? reason.substring(0, reason.indexOf(':')) : reason).increment();
        s.debugStage("plan", "degraded", s.sinceMs(startedAt), () -> Map.of("reason", reason));
        s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, null,
                ActivityEvent.Phase.PLAN, ActivityEvent.Status.SKIPPED, startedAt,
                s.sinceMs(startedAt), "规划不可用，改用固定检索流程", Map.of(), reason));
        s.next = "retrieve";
    }

    /** 执行：代码逐个调用工具（重新鉴权、幂等、预算），结果写入证据池。 */
    private void execute(Session s) {
        long nodeStartedAt = s.nowMs();
        var calls = s.pendingCalls;
        s.pendingCalls = List.of();
        var context = new ToolExecutionContext(s.run, s.pool, s.query, s.conversationHistory,
                graphSelection(s), s.queryRound);
        for (var call : calls) {
            String name = call.call().name();
            String step = stepId(s, "tool-" + (++s.roundToolCount) + "-" + name);
            long startedAt = s.nowMs();
            ActivityEvent.Phase phase = toolPhase(name);
            s.emitActivity(ActivityEvent.started(step, null, s.queryRound, null, phase, startedAt,
                    toolStartSummary(name)));
            ToolResult result = toolDispatcher.execute(call, context, s.toolResults, s.toolIdentities);
            s.toolHistory.add(new RetrievalPlannerPort.ToolExchange(call.call(), result));
            boolean ok = "SUCCESS".equals(result.status());
            s.emitActivity(ActivityEvent.finished(step, null, s.queryRound, null, phase,
                    ok ? ActivityEvent.Status.COMPLETED : ActivityEvent.Status.SKIPPED, startedAt,
                    s.sinceMs(startedAt), toolDoneSummary(name, result), toolActivityMetrics(s, name, result),
                    ok ? (result.degradations().isEmpty() ? null : result.degradations().get(0)) : result.errorCode()));
        }
        s.debugStage("observe", "pool", s.sinceMs(nodeStartedAt), () -> {
            Map<String, Object> fields = new LinkedHashMap<>(s.pool.summary());
            fields.put("poolVersion", s.pool.version());
            fields.put("planRound", s.planRound);
            return fields;
        });
        if (s.pool.clarification() != null) {
            emitClarification(s, s.pool.clarification(), nodeStartedAt, "clarify", ActivityEvent.Phase.PLAN);
            return;
        }
        // 证据有更新才值得重新生成；否则回到规划，让 planner 基于新的观察再决定
        if (!s.pool.hasChildren() || s.pool.version() == s.generatedPoolVersion) {
            s.next = "plan";
        } else if (s.autoExpandParents) {
            expandPoolParents(s);
            s.next = "generate";
        } else if (shouldOfferParents(s, calls)) {
            // 未开启自动补父块：检索带来了尚无父块的子块，先回 planner，让它决定 expand_parents 还是直接生成
            s.roundReplanned = true;
            s.continuePlanRound = true;
            s.next = "plan";
        } else {
            s.next = "generate";
        }
    }

    /** 每轮至多续规划一次；本批已调用过 expand_parents、子块都已有父块或预算不足时直接生成。 */
    private boolean shouldOfferParents(Session s, List<ToolRegistry.ValidatedAgentCall> calls) {
        // 续规划要花一次 planner 调用，还得留一次给生成
        if (s.roundReplanned || s.pool.parentFetches() >= limits.parentFetches()
                || !s.run.canModel(2) || !s.run.canTools(1)) {
            return false;
        }
        if (calls.stream().anyMatch(call -> AgentTools.EXPAND_PARENTS.equals(call.call().name()))) {
            return false;
        }
        Set<String> used = s.pool.usedParentKeys();
        return s.pool.children().stream().anyMatch(child -> !used.contains(child.parentChunkKey()));
    }

    private static ActivityEvent.Phase toolPhase(String tool) {
        return switch (tool) {
            case AgentTools.EXPAND_PARENTS -> ActivityEvent.Phase.PARENT_FETCH;
            case AgentTools.REWRITE_QUERY -> ActivityEvent.Phase.REWRITE;
            case AgentTools.ASK_USER -> ActivityEvent.Phase.PLAN;
            default -> ActivityEvent.Phase.RETRIEVAL;
        };
    }

    private static String toolStartSummary(String tool) {
        return switch (tool) {
            case AgentTools.SEARCH_CHUNKS -> "正在检索知识库";
            case AgentTools.EXPAND_PARENTS -> "正在补充完整上下文";
            case AgentTools.GRAPH_EXPAND -> "正在通过知识图谱扩展";
            case AgentTools.REWRITE_QUERY -> "正在改写检索问题";
            default -> "正在确认问题";
        };
    }

    private static String toolDoneSummary(String tool, ToolResult result) {
        if (!"SUCCESS".equals(result.status())) {
            return "未执行：" + result.errorCode();
        }
        Object added = result.stats().getOrDefault("addedCount", 0);
        return switch (tool) {
            case AgentTools.SEARCH_CHUNKS -> "检索新增 " + added + " 个片段";
            case AgentTools.EXPAND_PARENTS -> "补充 " + added + " 个完整段落";
            case AgentTools.GRAPH_EXPAND -> "图检索新增 " + added + " 个片段";
            case AgentTools.REWRITE_QUERY -> "已改写检索问题";
            default -> "需要你确认";
        };
    }

    private void rewrite(Session s) {
        // 改写预算 / 查询轮次预算耗尽 → 精确拒绝。
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
            // 无效或原样回显的改写消耗掉一次调用；若预算仍有余，则带
            // 校验失败信息重试一次。
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
        s.run.observe(() -> new RunObserver.RewriteObserved(s.queryRound, newQuery));
        s.stage = AttemptStage.BASE_CHILD; // 新一轮会重置基础 TopK
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
        for (String token : paragraphs(s.message)) {
            s.text.append(token);
            s.emit("token", Map.of("text", token));
        }
        s.emit("citations", Map.of("citations", List.of()));
        s.debugTerminal(s.outcome, s.outcome, () -> Map.of(
                "queryRound", s.queryRound,
                "stage", s.stage.name(),
                "answerChars", s.message.length(),
                "citationCount", 0));
        s.emitActivity(ActivityEvent.finished(stepId(s, "final"), null, s.queryRound,
                s.stage.name(), ActivityEvent.Phase.FINAL, ActivityEvent.Status.COMPLETED,
                s.nowMs(), 0L,
                AgenticErrorCodes.INSUFFICIENT.equals(s.outcome) ? "检索结束 · 未找到足够依据" : s.outcome,
                Map.of(), s.outcome));
        s.done(Map.of(
                "outcome", s.outcome,
                "noEvidence", AgenticErrorCodes.INSUFFICIENT.equals(s.outcome),
                "message", s.message,
                "route", s.routeState()));
        s.next = END;
    }

    // ------------------------------------------------------------------
    // 状态机辅助方法
    // ------------------------------------------------------------------

    /**
     * 固定的恢复顺序：base-child → base-parent → expanded-child →
     * expanded-parent → rewrite。扩展在每个查询轮次中只发生一次。
     */
    private void advanceRecovery(Session s) {
        AttemptStage next = s.stage.next();
        while (next != null && s.skipParentStages && next.isParentStage()) {
            next = next.next();
        }
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

    private static List<RunObserver.ContextSegment> contextSegments(boolean parentStage,
                                                                    List<ChildEvidence> children,
                                                                    List<ParentEvidence> parents) {
        return parentStage
                ? parents.stream().map(parent -> new RunObserver.ContextSegment(
                        parent.resourceType(), parent.resourceId(), parent.body())).toList()
                : children.stream().map(child -> new RunObserver.ContextSegment(
                        child.resourceType(), child.resourceId(), child.content())).toList();
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

    /** 把已评审的候选拆分成段落粒度的发布分块。 */
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
        /** 某个节点因取消而提前结束图；invoke 返回后据此发出 cancelled 终态。 */
        volatile boolean cancelledInNode;
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
        RouteInput routeInput = RouteInput.NONE;
        /** plan-execute 状态；planner 未启用时 pool 为 null。 */
        EvidencePool pool;
        int planRound, generatedPoolVersion = -1;
        /** 本轮已执行的工具数，用于活动步骤编号；续规划后继续累加。 */
        int roundToolCount;
        /** 下一次 plan 属于同一轮的续规划（检索后决定是否补父块）。 */
        boolean continuePlanRound;
        boolean roundReplanned;
        boolean plannerDegraded;
        /** run 开始时的 auto-expand-parents 快照。 */
        boolean autoExpandParents;
        QualityAssessment lastReview;
        /** planner 已把本次 run 改成直答：direct 不得再升级回检索。 */
        boolean rerouted;
        /** 固定阶梯：最近一次评审判定证据无关，跳过 *_PARENT 阶段。 */
        boolean skipParentStages;
        List<ToolRegistry.ValidatedAgentCall> pendingCalls = List.of();
        final Map<String, ToolResult> toolResults = new LinkedHashMap<>();
        final Map<String, String> toolIdentities = new LinkedHashMap<>();
        final List<RetrievalPlannerPort.ToolExchange> toolHistory = new ArrayList<>();
        final List<String> plannedTools = new ArrayList<>();

        boolean plannerMode() {
            return pool != null && !plannerDegraded;
        }
        AttemptStage stage = AttemptStage.BASE_CHILD;
        String currentQuery;
        String latestReason = "";
        String next = "route", outcome = AgenticErrorCodes.INSUFFICIENT,
                message = AgenticErrorCodes.INSUFFICIENT_MESSAGE;
        int queryRound = 1, rewritesUsed, hybridRetrievalUsed, parentFetchUsed,
                generationsUsed, qualityUsed, citationCount;
        final java.util.Set<Long> selectedKbIds;
        final java.util.Set<Long> selectedPageIds;
        final com.kwiki.rag.retrieval.GraphRetrievalEnhancer.SharedBudget graphBudget =
                new com.kwiki.rag.retrieval.GraphRetrievalEnhancer.SharedBudget();

        Session(RunContext run, CurrentUser user, String query, List<ChatTurn> conversationHistory, FluxSink<ChatStreamEvent> sink, boolean persistConversation,
                java.util.Set<Long> selectedKbIds, java.util.Set<Long> selectedPageIds) {
            this.persistConversation = persistConversation;
            this.run = run;
            this.user = user;
            this.query = query;
            this.conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
            this.currentQuery = query == null ? "" : query;
            this.sink = sink;
            this.selectedKbIds = selectedKbIds == null ? java.util.Set.of() : selectedKbIds;
            this.selectedPageIds = selectedPageIds == null ? java.util.Set.of() : selectedPageIds;
        }

        RouteContext routeContext() {
            List<String> recent = new ArrayList<>();
            for (ChatTurn turn : conversationHistory) {
                if (turn.isUser() && turn.content() != null && !turn.content().isBlank()) {
                    recent.add(turn.content().strip());
                }
            }
            int from = Math.max(0, recent.size() - 6);
            List<String> bounded = recent.subList(from, recent.size()).stream()
                    .map(q -> q.length() <= 200 ? q : q.substring(0, 200))
                    .toList();
            return new RouteContext(routeInput.prior(), bounded, routeInput.clarificationResumed());
        }

        /** 最终路由状态（done 事件携带，供持久化到 chat_run）；尚无路由时为空。 */
        Map<String, Object> routeState() {
            return route == null ? Map.of() : PriorRoute.of(route).toMap();
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
            debugTerminal(errorCode, errorCode, () -> Map.of(
                    "queryRound", queryRound,
                    "stage", stage.name(),
                    "currentQuery", AgenticDebugLogger.boundedQuery(currentQuery),
                    "nodes", List.copyOf(nodes)));
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
                    parent.matchedChildren().stream().findFirst().ifPresent(child -> {
                        var item = new LinkedHashMap<String, Object>();
                        item.put("childChunkKey", child.chunkKey());
                        item.put("parentChunkKey", parent.parentChunkKey());
                        item.put("resourceType", parent.resourceType());
                        item.put("resourceId", parent.resourceId());
                        item.put("kbId", parent.kbId());
                        item.put("citationId", "P" + parent.firstHitOrder());
                        item.put("displayName", citationNames.resolve(parent.resourceType(), parent.resourceId(), child.headingPath()));
                        item.put("revisionId", parent.revisionId());
                        item.put("headingPath", child.headingPath());
                        item.put("charStart", child.charStart());
                        item.put("charEnd", child.charEnd());
                        item.put("excerpt", excerpt(child.content(), 120));
                        citations.add(item);
                    });
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
                    item.put("kbId", child.kbId());
                    item.put("citationId", "P" + i);
                    item.put("displayName", citationNames.resolve(child.resourceType(), child.resourceId(), child.headingPath()));
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
                trace.put("route", routeState());
                trace.put("planRounds", planRound);
                trace.put("plannerDegraded", plannerDegraded);
                trace.put("plannedTools", List.copyOf(plannedTools));
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
