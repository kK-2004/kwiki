package com.kwiki.eval;

import com.kwiki.rag.answer.ChatStreamEvent;
import com.kwiki.rag.orchestration.AgenticWorkflowPort;
import com.kwiki.rag.orchestration.RouteInput;
import com.kwiki.rag.retrieval.QaRetrievalBudgets;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.AuthorizationScope;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 中断 / 继续 / 删除 / 接管：用真实 WorkflowProbe + 可控的工作流 Flux 验证在途题被取消。 */
class EvalRunLifecycleTest {

    final EvalRunTestContext ctx = new EvalRunTestContext();
    final InMemoryEvalRunStore store = ctx.store;
    final CurrentUser admin = EvalRunTestContext.admin();
    final EvalDatasetLoader loader = mock(EvalDatasetLoader.class);
    final BaselineRetrievalProbe baseline = mock(BaselineRetrievalProbe.class);
    final AgenticWorkflowPort agent = mock(AgenticWorkflowPort.class);
    final AuthorizationScopeResolver scopes = mock(AuthorizationScopeResolver.class);
    final EvalCorpusService corpus = mock(EvalCorpusService.class);
    final InMemoryReport report = new InMemoryReport();
    /** 慢题：订阅后挂起，直到被取消；记录订阅与取消。 */
    final Set<String> slow = ConcurrentHashMap.newKeySet();
    final Map<String, CountDownLatch> subscribed = new ConcurrentHashMap<>();
    final Map<String, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
    final List<EvalWorker> workers = new CopyOnWriteArrayList<>();

    static EvalCase positive(String id, String query) {
        return new EvalCase(id, "dragonball", 50, "事实性问题", "Finance", query,
                List.of(List.of("10000吨")), false, List.of(5), List.of());
    }

    EvalRunService service(Clock clock) {
        when(scopes.resolve(eq(admin), eq(Set.of(7L)), eq(Set.of())))
                .thenReturn(new AuthorizationScope(1L, false, Set.of(7L), Map.of()));
        return ctx.service(loader, baseline, new WorkflowProbe(agent), scopes, corpus, report,
                new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 12000, 24000, 24000),
                ctx.instanceA, 2, 2, List.of("无法回答"), clock);
    }

    EvalRunService service() {
        return service(Clock.systemUTC());
    }

    /** 手动启动认领线程（50ms 轮询），测毕统一停止。 */
    EvalWorker startWorker(EvalRunService service) {
        EvalWorker worker = ctx.worker(service, ctx.instanceA, 2, Duration.ofMillis(50));
        worker.start();
        workers.add(worker);
        return worker;
    }

    @AfterEach
    void stopWorkers() {
        workers.forEach(EvalWorker::stop);
        workers.clear();
    }

    void stubCases(String... queries) {
        when(corpus.pageDocIds(admin, 7L, EvalCorpusService.DEFAULT_CORPUS)).thenReturn(Map.of(1L, 5));
        List<EvalCase> cases = new ArrayList<>();
        for (int i = 0; i < queries.length; i++) cases.add(positive("c-" + i, queries[i]));
        when(loader.load()).thenReturn(cases);
        when(baseline.probe(anyString(), any())).thenReturn(new BaselineRetrievalProbe.Result(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        when(agent.answerInSession(eq(admin), anyString(), anyList(), eq(Set.of(7L)), eq(Set.of()),
                eq(RouteInput.NONE), any())).thenAnswer(invocation -> {
                    String query = invocation.getArgument(1);
                    if (!slow.contains(query)) {
                        return Flux.just(ChatStreamEvent.of("token", 1, "r", Map.of("text", "10000吨")),
                                ChatStreamEvent.of("done", 2, "r", Map.of("outcome", "completed")));
                    }
                    return Flux.<ChatStreamEvent>never()
                            .doOnSubscribe(s -> subscribed.computeIfAbsent(query, q -> new CountDownLatch(1))
                                    .countDown())
                            .doOnCancel(() -> cancelled.computeIfAbsent(query, q -> new AtomicBoolean()).set(true));
                });
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).as("condition reached within 5s").isTrue();
    }

    CountDownLatch subscription(String query) {
        return subscribed.computeIfAbsent(query, q -> new CountDownLatch(1));
    }

    static String traceId(String runName, String caseId) {
        return UUID.nameUUIDFromBytes((runName + ":" + caseId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Test
    void cancelStopsInFlightCasesImmediatelyAndKeepsCompletedOnes() throws Exception {
        stubCases("快题", "慢题一", "慢题二");
        slow.addAll(List.of("慢题一", "慢题二"));
        var service = service();
        startWorker(service);

        var started = service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-x", null, false));
        // 并发 2：快题完成后两道慢题都在执行
        assertThat(subscription("慢题一").await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(subscription("慢题二").await(5, TimeUnit.SECONDS)).isTrue();
        await(() -> store.caseCount(started.runId()) == 1);

        var status = service.cancel(started.runId());

        assertThat(status.state()).isEqualTo(EvalRunState.CANCELLED);
        assertThat(status.completed()).isEqualTo(1);
        await(() -> cancelled.containsKey("慢题一") && cancelled.containsKey("慢题二"));
        assertThat(store.cases(started.runId())).containsOnlyKeys("c-0");
        assertThat(report.traces).extracting(EvalReportPort.TraceRecord::name).containsExactly("eval:c-0");
        assertThat(report.batches).as("中断时上报已缓冲的结果").containsExactly(1);
        assertThat(status.summary().total()).isEqualTo(1);
        // 中断后可立即启动新 run（本实例名额与集群名额已释放）
        slow.clear();
        var next = service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-y", null, true));
        await(() -> service.status(next.runId()).state() == EvalRunState.COMPLETED);
    }

    @Test
    void resumeRunsOnlyUnfinishedCasesUnderTheSameRunName() throws Exception {
        stubCases("快题", "慢题一", "慢题二");
        slow.addAll(List.of("慢题一", "慢题二"));
        var service = service();
        startWorker(service);
        var started = service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-r", "基线", false));
        assertThat(subscription("慢题二").await(5, TimeUnit.SECONDS)).isTrue();
        await(() -> store.caseCount(started.runId()) == 1);
        service.cancel(started.runId());

        slow.clear();
        var resumed = service.resume(admin, started.runId());

        assertThat(resumed.runId()).isEqualTo(started.runId());
        assertThat(resumed.state()).isEqualTo(EvalRunState.QUEUED);
        await(() -> service.status(started.runId()).state() == EvalRunState.COMPLETED);
        var done = service.status(started.runId());
        assertThat(done.completed()).isEqualTo(3);
        assertThat(done.summary().total()).isEqualTo(3);
        assertThat(done.runName()).isEqualTo("run-r");
        verify(baseline, times(1)).probe(eq("快题"), any());
        verify(baseline, times(2)).probe(eq("慢题一"), any());
        assertThat(report.traces).extracting(EvalReportPort.TraceRecord::runName).containsOnly("run-r");
        assertThat(report.traces).extracting(EvalReportPort.TraceRecord::name)
                .containsExactlyInAnyOrder("eval:c-0", "eval:c-1", "eval:c-2");
        @SuppressWarnings("unchecked")
        var cases = (List<CaseResult>) service.report(started.runId()).get("cases");
        assertThat(cases).extracting(result -> result.evalCase().id()).containsExactly("c-0", "c-1", "c-2");
        assertThat(service.report(started.runId())).containsEntry("runName", "run-r")
                .containsKey("summary").containsKey("config");
        assertThat(store.reportedCaseIds(started.runId())).containsExactlyInAnyOrder("c-0", "c-1", "c-2");
    }

    @Test
    void onlyCancelledOrFailedRunsCanBeResumedAndRunningRunsCannotBeDeleted() throws Exception {
        stubCases("慢题一");
        slow.add("慢题一");
        var service = service();
        startWorker(service);
        var started = service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-s", null, true));
        assertThat(subscription("慢题一").await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.resume(admin, started.runId())).hasMessageContaining("eval-run-not-resumable");
        assertThatThrownBy(() -> service.delete(started.runId(), true)).hasMessageContaining("eval-run-running");
        service.cancel(started.runId());
        assertThatThrownBy(() -> service.cancel(started.runId())).hasMessageContaining("eval-run-not-running");
        assertThatThrownBy(() -> service.resume(admin, "nope")).hasMessageContaining("eval-run-not-found");
    }

    @Test
    void deleteRemovesStoredRunAndOptionallyLangfuseTraces() {
        stubCases("快题", "另一道快题");
        var service = service();
        var status = service.runNow(admin, new EvalRunService.StartRequest("50q", 7L, "run-d", null, false));

        var result = service.delete(status.runId(), true);

        assertThat(result.langfuseDeleted()).isTrue();
        assertThat(report.deletedRuns).containsExactly("kwiki-rag-50q/run-d");
        assertThat(report.deletedTraceIds).containsExactlyInAnyOrder(traceId("run-d", "c-0"), traceId("run-d", "c-1"));
        assertThat(store.find(status.runId())).isEmpty();
        assertThat(store.cases(status.runId())).isEmpty();
        assertThatThrownBy(() -> service.status(status.runId())).hasMessageContaining("eval-run-not-found");
        assertThat(service.list()).isEmpty();
    }

    @Test
    void deleteKeepsLangfuseWhenNotRequestedAndReportsLangfuseFailure() {
        stubCases("快题");
        var service = service();
        var keep = service.runNow(admin, new EvalRunService.StartRequest("50q", 7L, "run-k", null, false));
        var local = service.runNow(admin, new EvalRunService.StartRequest("50q", 7L, "run-l", null, true));
        var broken = service.runNow(admin, new EvalRunService.StartRequest("50q", 7L, "run-b", null, false));

        assertThat(service.delete(keep.runId(), false).langfuseDeleted()).isFalse();
        assertThat(service.delete(local.runId(), true).langfuseDeleted()).as("localOnly 没有上报").isFalse();
        assertThat(report.deletedRuns).isEmpty();
        report.failDelete = true;
        var failed = service.delete(broken.runId(), true);
        assertThat(failed.langfuseDeleted()).isFalse();
        assertThat(failed.langfuseError()).contains("langfuse down");
        assertThat(store.find(broken.runId())).as("Langfuse 失败不阻止本地删除").isEmpty();
    }

    @Test
    void staleRunningRunFromAnotherProcessIsTreatedAsCancelledAndResumable() throws Exception {
        stubCases("快题", "第二题");
        Instant now = Instant.parse("2026-10-21T10:00:00Z");
        store.create(new EvalRunStore.RunMeta("old-run", "run-old", "50q", 7L, "", true, 2,
                Map.of("dataset", "50q"), now.minus(Duration.ofMinutes(10)), "1"));
        // 种子为「另一实例执行中且心跳已过期」：先认领（置 RUNNING、发放令牌 1）再写检查点
        assertThat(store.claimOwnership("old-run", "other-instance", now.minus(Duration.ofMinutes(10)),
                Duration.ofMinutes(2).toMillis()))
                .isEqualTo(1L);
        store.saveCase("old-run", 1L, new CaseResult(positive("c-0", "快题"), null, null, null, "", List.of(),
                Map.of(), Map.of(), "boom", 1));
        var service = service(Clock.fixed(now, ZoneOffset.UTC));

        var status = service.status("old-run");
        assertThat(status.state()).isEqualTo(EvalRunState.CANCELLED);
        assertThat(status.error()).isEqualTo("interrupted");
        assertThat(service.list()).extracting(EvalRunService.RunStatus::state).containsExactly(EvalRunState.CANCELLED);

        var resumed = service.resume(admin, "old-run");
        assertThat(resumed.state()).isEqualTo(EvalRunState.QUEUED);
        startWorker(service);
        await(() -> service.status("old-run").state() == EvalRunState.COMPLETED);
        verify(baseline, times(0)).probe(eq("快题"), any());
        assertThat(service.status("old-run").completed()).isEqualTo(2);
    }

    @Test
    void cancelFromAnotherInstanceIsDeliveredImmediatelyViaControlBus() throws Exception {
        stubCases("慢题一");
        slow.add("慢题一");
        var owner = service();
        owner.heartbeatInterval(Duration.ofMillis(50));
        startWorker(owner);
        var other = service();
        var started = owner.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-h", null, true));
        assertThat(subscription("慢题一").await(5, TimeUnit.SECONDS)).isTrue();

        other.cancel(started.runId());

        await(() -> owner.status(started.runId()).state() == EvalRunState.CANCELLED);
        await(() -> cancelled.containsKey("慢题一"));
    }

    @Test
    void cancelFlagWithoutNotificationIsPickedUpByHeartbeat() throws Exception {
        stubCases("慢题一");
        slow.add("慢题一");
        var owner = service();
        owner.heartbeatInterval(Duration.ofMillis(50));
        startWorker(owner);
        var started = owner.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-i", null, true));
        assertThat(subscription("慢题一").await(5, TimeUnit.SECONDS)).isTrue();

        // 模拟控制通知丢失：另一实例只写了取消标志
        store.requestCancel(started.runId());

        await(() -> owner.status(started.runId()).state() == EvalRunState.CANCELLED);
        await(() -> cancelled.containsKey("慢题一"));
    }

    @Test
    void cancelQueuedRunFinishesItWithoutExecuting() throws Exception {
        stubCases("快题");
        var service = service(); // 不启动认领线程，保持排队
        var started = service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-q", null, true));
        assertThat(service.status(started.runId()).state()).isEqualTo(EvalRunState.QUEUED);

        var status = service.cancel(started.runId());

        assertThat(status.state()).isEqualTo(EvalRunState.CANCELLED);
        assertThat(store.caseCount(started.runId())).isZero();
        verify(baseline, times(0)).probe(anyString(), any());
        // 排队中取消后可重新继续
        assertThat(service.resume(admin, started.runId()).state()).isEqualTo(EvalRunState.QUEUED);
    }
}
