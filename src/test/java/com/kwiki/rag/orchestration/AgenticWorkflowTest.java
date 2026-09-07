package com.kwiki.rag.orchestration;

import static org.assertj.core.api.Assertions.*;

import com.kwiki.infrastructure.orchestration.LangGraphAgenticWorkflow;
import com.kwiki.rag.answer.*;
import com.kwiki.rag.quality.*;
import com.kwiki.rag.retrieval.*;
import com.kwiki.rag.rewrite.*;
import com.kwiki.rag.routing.*;
import com.kwiki.rag.tool.*;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.*;

import org.junit.jupiter.api.*;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class AgenticWorkflowTest {
    final List<LangGraphAgenticWorkflow> workflows = new ArrayList<>();
    final AtomicInteger plannerCalls = new AtomicInteger(),
            qaCalls = new AtomicInteger(),
            answerCalls = new AtomicInteger();
    RetrievalPlannerPort planner =
            (q, queries, gaps, history, error) ->
                    List.of(
                            new ToolCall(
                                    "call-" + plannerCalls.incrementAndGet(),
                                    "es_search",
                                    ToolRegistry.json(
                                            new SearchArguments(
                                                    queries, RetrievalStrategy.BM25, 20)),
                                    "turn-" + plannerCalls.get()));
    QualityAnalyzerPort qa = (q, queries, e) -> approved(e);
    java.util.function.Function<String, List<ChunkHit>> corpus =
            q -> List.of(new ChunkHit("C" + q, "P" + q, 1, "PAGE", 1, 1L, "heading", 0, 4, "body"));
    AnswerLlmPort answer =
            p -> {
                answerCalls.incrementAndGet();
                return Flux.just("answer [P0]");
            };
    AgenticLimits limits = AgenticLimits.defaults();

    @AfterEach
    void close() {
        workflows.forEach(LangGraphAgenticWorkflow::close);
    }

    QualityDecision approved(List<ParentEvidence> e) {
        return new QualityDecision(
                QualityDecision.Action.GENERATE,
                true,
                e.stream().map(ParentEvidence::parentChunkKey).toList(),
                List.of(),
                "sufficient",
                List.of(),
                QualityDecision.ReturnKind.NONE);
    }

    LangGraphAgenticWorkflow workflow() throws Exception {
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var versions = new ScopeVersionService(StandardTestProperties.nullProvider());
        var budgets = new RetrievalBudgets(50, 40, 8, 3, 24000, Duration.ofSeconds(1));
        var registry = new ToolRegistry();
        var retrieval =
                new HybridRetrievalOrchestrator(
                        texts -> List.of(new float[] {1}),
                        new ConcurrentRecallService(
                                (q, v, f, k) -> corpus.apply(q), (q, v, f, k) -> corpus.apply(q)),
                        new ParentEvidenceResolver(
                                Optional.of(
                                        (keys, filter) ->
                                                keys.stream()
                                                        .map(
                                                                k ->
                                                                        new ParentEvidenceChunk(
                                                                                k, 1, "PAGE", 1, 1L,
                                                                                "heading", "body",
                                                                                0, List.of()))
                                                        .toList())),
                        versions,
                        budgets);
        var w =
                new LangGraphAgenticWorkflow(
                        new RuleFirstRouter(
                                KeywordRuleSet.defaults(),
                                StandardTestProperties.nullProvider(),
                                false,
                                metrics),
                        new QueryRewriteOrchestrator(
                                new RewriteDecisionService(),
                                new ConversationalRewriter(Optional.empty()),
                                new ExpansionRewriter(Optional.empty()),
                                new DecompositionRewriter(Optional.empty()),
                                metrics),
                        retrieval,
                        new EvidenceAssembler(budgets),
                        p -> answer.streamAnswer(p),
                        (q, queries, e) -> {
                            qaCalls.incrementAndGet();
                            return qa.analyze(q, queries, e);
                        },
                        (q, queries, gaps, history, error) ->
                                planner.plan(q, queries, gaps, history, error),
                        registry,
                        new ManualToolDispatcher(registry, retrieval),
                        new AuthorizationScopeResolver(
                                org.mockito.Mockito.mock(
                                        com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository
                                                .class),
                                versions,
                                new com.kwiki.infrastructure.redis.ScopeCache(
                                        StandardTestProperties.nullProvider(),
                                        Duration.ofSeconds(1))),
                        versions,
                        new ChatPersistenceService(StandardTestProperties.nullProvider()),
                        metrics,
                        limits,
                        Optional.empty(),
                        Optional.empty());
        workflows.add(w);
        return w;
    }

    List<ChatStreamEvent> run(String query) throws Exception {
        return workflow()
                .answer(new CurrentUser(1L, "admin", true), query)
                .collectList()
                .block(Duration.ofSeconds(5));
    }

    @Test
    void qaGapDrivesASecondActualRetrieval() throws Exception {
        qa =
                (q, queries, e) ->
                        qaCalls.get() == 1
                                ? new QualityDecision(
                                        QualityDecision.Action.RETRY,
                                        false,
                                        List.of(),
                                        List.of("second aspect"),
                                        "missing-aspect",
                                        List.of("second query"),
                                        QualityDecision.ReturnKind.NONE)
                                : approved(e);
        var events = run("什么是部署方式");
        assertThat(events.stream().filter(e -> e.type().equals("retrieve"))).hasSize(2);
        assertThat(events.stream().filter(e -> e.type().equals("quality"))).hasSize(2);
        assertThat(events.stream().map(ChatStreamEvent::type)).contains("retry");
        assertThat(answerCalls.get()).isEqualTo(1);
        assertThat(events.getLast().type()).isEqualTo("done");
    }

    @Test
    void repeatedPlanStopsWithoutGeneration() throws Exception {
        qa =
                (q, queries, e) ->
                        new QualityDecision(
                                QualityDecision.Action.RETRY,
                                false,
                                List.of(),
                                List.of("gap"),
                                "missing-aspect",
                                List.of(),
                                QualityDecision.ReturnKind.NONE);
        var events = run("什么是部署方式");
        assertThat(answerCalls.get()).isZero();
        assertThat(qaCalls.get()).isEqualTo(1);
        assertThat(events.getLast().payloadMap()).containsEntry("noEvidence", true);
    }

    @Test
    void invalidQaCannotReleaseGeneration() throws Exception {
        qa =
                (q, queries, e) ->
                        new QualityDecision(
                                QualityDecision.Action.GENERATE,
                                true,
                                List.of("invented"),
                                List.of(),
                                "sufficient",
                                List.of(),
                                QualityDecision.ReturnKind.NONE);
        run("什么是部署方式");
        assertThat(answerCalls.get()).isZero();
    }

    @Test
    void emptyFirstRoundCanRecoverWithChangedQuery() throws Exception {
        corpus =
                q ->
                        q.equals("second query")
                                ? List.of(
                                        new ChunkHit(
                                                "C2", "P2", 1, "PAGE", 1, 1L, "h", 0, 4, "body"))
                                : List.of();
        var original = planner;
        planner =
                (q, queries, gaps, history, error) ->
                        original.plan(
                                q,
                                history.isEmpty() ? queries : List.of("second query"),
                                gaps,
                                history,
                                error);
        var events = run("什么是部署方式");
        assertThat(answerCalls.get()).isEqualTo(1);
        assertThat(events.getLast().type()).isEqualTo("done");
    }

    @Test
    void invalidToolBatchRepairsOnceThenUsesValidatedFallback() throws Exception {
        planner =
                (q, queries, gaps, history, error) -> {
                    plannerCalls.incrementAndGet();
                    return List.of(new ToolCall("bad", "es_search", "{\"scope\":{}}", "turn"));
                };
        var events = run("什么是部署方式");
        assertThat(plannerCalls.get()).isEqualTo(2);
        assertThat(answerCalls.get()).isEqualTo(1);
        assertThat(
                        events.stream()
                                .filter(e -> e.type().equals("tool"))
                                .findFirst()
                                .orElseThrow()
                                .payloadMap()
                                .get("callId"))
                .hasToString("fallback-0");
    }

    @Test
    void safeDirectResponseDoesNotCallModelsOrTools() throws Exception {
        run("你好");
        assertThat(plannerCalls.get() + qaCalls.get() + answerCalls.get()).isZero();
    }

    @Test
    void unknownCitationTerminatesWithError() throws Exception {
        answer = p -> Flux.just("answer [P999]");
        var events = run("什么是部署方式");
        assertThat(events.getLast().payloadMap())
                .containsEntry("error", "answer-validation-failed");
        assertThat(events.stream().map(ChatStreamEvent::type)).doesNotContain("citations", "done");
    }

    @Test
    void globalDeadlineStopsPendingQa() throws Exception {
        limits = new AgenticLimits(3, 16, 9, 3, 64, Duration.ofMillis(150), 256, 16);
        qa =
                (q, queries, e) -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException x) {
                        Thread.currentThread().interrupt();
                    }
                    return approved(e);
                };
        var events = run("什么是部署方式");
        assertThat(events.getLast().payloadMap()).containsEntry("error", "timeout");
        assertThat(answerCalls.get()).isZero();
        assertThat(events.stream().filter(e -> List.of("error", "done").contains(e.type())))
                .hasSize(1);
    }

    @Test
    void cancellationDuringPlannerStopsWorkerBeforeQa() throws Exception {
        var entered = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        planner =
                (q, queries, gaps, history, error) -> {
                    entered.countDown();
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        stopped.countDown();
                    }
                    return List.of();
                };
        var subscription =
                workflow().answer(new CurrentUser(1L, "admin", true), "什么是部署方式").subscribe();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();
        assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(qaCalls).hasValue(0);
        assertThat(answerCalls).hasValue(0);
    }

    @Test
    void thirdInsufficientRoundNeverGenerates() throws Exception {
        qa =
                (q, queries, e) ->
                        new QualityDecision(
                                QualityDecision.Action.RETRY,
                                false,
                                List.of(),
                                List.of("gap"),
                                "missing",
                                List.of("query-" + qaCalls.get()),
                                QualityDecision.ReturnKind.NONE);
        var events = run("什么是部署方式");
        assertThat(qaCalls).hasValue(3);
        assertThat(answerCalls).hasValue(0);
        assertThat(events.getLast().payloadMap()).containsEntry("outcome", "insufficient");
    }

    @Test
    void boundedBufferOverflowCancelsGenerationAndHasOneTerminal() throws Exception {
        limits = new AgenticLimits(3, 16, 9, 3, 64, Duration.ofSeconds(5), 16, 16);
        var stopped = new CountDownLatch(1);
        answer = p -> Flux.range(0, 1000).map(i -> "text").doFinally(signal -> stopped.countDown());
        var events = new CopyOnWriteArrayList<ChatStreamEvent>();
        var finished = new CountDownLatch(1);
        var subscriber =
                new reactor.core.publisher.BaseSubscriber<ChatStreamEvent>() {
                    protected void hookOnSubscribe(org.reactivestreams.Subscription s) {
                        request(1);
                    }

                    protected void hookOnNext(ChatStreamEvent event) {
                        events.add(event);
                    }

                    protected void hookOnComplete() {
                        finished.countDown();
                    }
                };
        workflow().answer(new CurrentUser(1L, "admin", true), "什么是部署方式").subscribe(subscriber);
        assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
        subscriber.request(Long.MAX_VALUE);
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(events.getLast().payloadMap()).containsEntry("error", "backpressure-overflow");
        assertThat(events.stream().filter(e -> Set.of("done", "error").contains(e.type())))
                .hasSize(1);
    }

    @Test
    void cancellationDuringRecallAndQaPreventsGeneration() throws Exception {
        for (String stage : List.of("recall", "qa")) {
            var entered = new CountDownLatch(1);
            var stopped = new CountDownLatch(1);
            Runnable blocking =
                    () -> {
                        entered.countDown();
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            stopped.countDown();
                        }
                        throw new RunFailure("cancelled");
                    };
            corpus =
                    q -> {
                        if (stage.equals("recall")) blocking.run();
                        return List.of(
                                new ChunkHit("C", "P", 1, "PAGE", 1, 1L, "heading", 0, 4, "body"));
                    };
            qa =
                    (q, queries, e) -> {
                        blocking.run();
                        return approved(e);
                    };
            var subscription =
                    workflow().answer(new CurrentUser(1L, "admin", true), "什么是部署方式").subscribe();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            subscription.dispose();
            assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(answerCalls).hasValue(0);
        }
    }

    @Test
    void compareLegacySinglePassPolicyOnLabelledFixtures() throws Exception {
        var cases =
                ToolRegistry.JSON.readTree(
                        getClass().getResourceAsStream("/agentic/evaluation-cases.json"));
        var report = new ArrayList<Map<String, Object>>();
        for (var item : cases) {
            plannerCalls.set(0);
            qaCalls.set(0);
            answerCalls.set(0);
            String kind = item.get("kind").asText();
            corpus =
                    q ->
                            kind.equals("empty")
                                    ? List.of()
                                    : List.of(
                                            new ChunkHit(
                                                    "C" + q, "P" + q, 1, "PAGE", 1, 1L, "h", 0, 4,
                                                    "body"));
            qa =
                    (q, queries, e) -> {
                        if (kind.equals("conflict") || kind.equals("injection"))
                            return QualityDecision.insufficient(
                                    kind.equals("conflict")
                                            ? "conflicting-evidence"
                                            : "untrusted-instruction");
                        if (kind.equals("retry") && qaCalls.get() == 1)
                            return new QualityDecision(
                                    QualityDecision.Action.RETRY,
                                    false,
                                    List.of(),
                                    List.of("permissions"),
                                    "missing-aspect",
                                    List.of("permissions details"),
                                    QualityDecision.ReturnKind.NONE);
                        return approved(e);
                    };
            String query = item.get("query").asText();
            long before = System.nanoTime();
            // Reference of the pre-change single-pass evidence-presence policy, not a live legacy
            // model run.
            String legacy = corpus.apply(query).isEmpty() ? "insufficient" : "completed";
            long legacyNanos = System.nanoTime() - before;
            before = System.nanoTime();
            var events = run(query);
            long newNanos = System.nanoTime() - before;
            String result =
                    events.getLast().payloadMap().getOrDefault("outcome", "completed").toString();
            assertThat(events.getLast().type()).as(item.get("id").asText()).isEqualTo("done");
            assertThat(result).isEqualTo(item.get("expected").asText());
            int citations =
                    events.stream()
                            .filter(e -> e.type().equals("citations"))
                            .mapToInt(e -> ((List<?>) e.payloadMap().get("citations")).size())
                            .sum();
            report.add(
                    Map.of(
                            "case",
                            item.get("id").asText(),
                            "legacyPolicy",
                            legacy,
                            "newOutcome",
                            result,
                            "expected",
                            item.get("expected").asText(),
                            "retrievalRounds",
                            events.stream().filter(e -> e.type().equals("retrieve")).count(),
                            "citations",
                            citations,
                            "legacyPolicyNanos",
                            legacyNanos,
                            "newWorkflowNanos",
                            newNanos));
        }
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target/agentic-evaluation.json"),
                ToolRegistry.json(
                        Map.of(
                                "mode",
                                "scripted QA and retrieval; policy comparison only, not model"
                                        + " quality or production latency",
                                "cases",
                                report)));
    }

    @Test
    void coldPublisherDoesNoPlanning() throws Exception {
        workflow().answer(new CurrentUser(1L, "admin", true), "question");
        assertThat(plannerCalls.get()).isZero();
    }
}
