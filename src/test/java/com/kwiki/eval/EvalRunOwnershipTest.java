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

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 写入令牌（设计 §6）与上报不丢失（设计 §8）：接管后旧实例的检查点 / 心跳 / 终态写入被拒并静默退出；
 * 有检查点未上报的题在继续时先补报；upload 部分成功时只标记成功的题，下次继续只补缺失的。
 */
class EvalRunOwnershipTest {

    final EvalRunTestContext ctx = new EvalRunTestContext();
    final InMemoryEvalRunStore store = ctx.store;
    final CurrentUser admin = EvalRunTestContext.admin();
    final EvalDatasetLoader loader = mock(EvalDatasetLoader.class);
    final BaselineRetrievalProbe baseline = mock(BaselineRetrievalProbe.class);
    final AgenticWorkflowPort agent = mock(AgenticWorkflowPort.class);
    final AuthorizationScopeResolver scopes = mock(AuthorizationScopeResolver.class);
    final EvalCorpusService corpus = mock(EvalCorpusService.class);
    final InMemoryReport report = new InMemoryReport();
    final List<EvalWorker> workers = new CopyOnWriteArrayList<>();
    final Map<String, CountDownLatch> subscribed = new ConcurrentHashMap<>();
    final Map<String, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
    /** 慢题只挂起第一次调用（旧实例执行时）；接管后的重跑立即完成。 */
    final AtomicBoolean hangUsed = new AtomicBoolean();

    @AfterEach
    void stopWorkers() {
        workers.forEach(EvalWorker::stop);
        workers.clear();
    }

    static EvalCase positive(String id, String query) {
        return new EvalCase(id, "dragonball", 50, "事实性问题", "Finance", query,
                List.of(List.of("10000吨")), false, List.of(5), List.of());
    }

    /** slowQuery 的第一次执行订阅后挂起（记录订阅与取消），之后的执行与其余题立即完成。 */
    void stub(String slowQuery, String... queries) {
        hangUsed.set(false);
        when(corpus.pageDocIds(admin, 7L, EvalCorpusService.DEFAULT_CORPUS)).thenReturn(Map.of(1L, 5));
        List<EvalCase> cases = new java.util.ArrayList<>();
        for (int i = 0; i < queries.length; i++) cases.add(positive("o-" + i, queries[i]));
        when(loader.load()).thenReturn(cases);
        when(baseline.probe(anyString(), any())).thenReturn(new BaselineRetrievalProbe.Result(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        when(agent.answerInSession(eq(admin), anyString(), anyList(), eq(Set.of(7L)), eq(Set.of()),
                eq(RouteInput.NONE), any())).thenAnswer(invocation -> {
                    String query = invocation.getArgument(1);
                    boolean firstHang = slowQuery.equals(query) && hangUsed.compareAndSet(false, true);
                    if (!firstHang) {
                        return Flux.just(ChatStreamEvent.of("token", 1, "r", Map.of("text", "10000吨")),
                                ChatStreamEvent.of("done", 2, "r", Map.of("outcome", "completed")));
                    }
                    return Flux.<ChatStreamEvent>never()
                            .doOnSubscribe(s -> subscribed.computeIfAbsent(query, q -> new CountDownLatch(1))
                                    .countDown())
                            .doOnCancel(() -> cancelled.computeIfAbsent(query, q -> new AtomicBoolean()).set(true));
                });
    }

    EvalRunService service(EvalInstanceId instance) {
        when(scopes.resolve(eq(admin), eq(Set.of(7L)), eq(Set.of())))
                .thenReturn(new AuthorizationScope(1L, false, Set.of(7L), Map.of()));
        return ctx.service(loader, baseline, new WorkflowProbe(agent), scopes, corpus, report,
                new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 12000, 24000, 24000),
                instance, 2, 2, List.of("无法回答"), Clock.systemUTC());
    }

    EvalWorker startWorker(EvalRunService service, EvalInstanceId instance) {
        EvalWorker worker = ctx.worker(service, instance, 2, Duration.ofMillis(20));
        worker.start();
        workers.add(worker);
        return worker;
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).as("condition reached within 5s").isTrue();
    }

    CountDownLatch subscribed(String query) {
        return subscribed.computeIfAbsent(query, q -> new CountDownLatch(1));
    }

    @Test
    void takeoverRejectsOldInstanceWritesAndOldExecutionExitsSilently() throws Exception {
        stub("挂起题", "挂起题", "其余题目");
        var owner = service(ctx.instanceA);
        owner.heartbeatInterval(Duration.ofMillis(50));
        startWorker(owner, ctx.instanceA);
        var started = owner.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-t", null, true));
        assertThat(subscribed("挂起题").await(5, TimeUnit.SECONDS)).isTrue();
        await(() -> store.caseCount(started.runId()) == 1);

        // 模拟实例 A 失联（如长时间 GC 停顿）后被接管：B 的时钟超前 3 分钟，看到 A 的心跳已过期
        // （心跳新鲜的 run 不允许被抢占）；run 锁随失联进程失效，这里用独立锁实例代替。
        // B 取得新令牌后，A 恢复时的后续写入因令牌不符被拒
        var takeover = new EvalRunService(loader, baseline, new WorkflowProbe(agent), scopes, corpus, report,
                store, ctx.queue, ctx.slots, ctx.bus, new InMemoryEvalRunLocks(),
                new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 12000, 24000, 24000), ctx.instanceB, 2, 2,
                List.of("无法回答"), Clock.offset(Clock.systemUTC(), Duration.ofMinutes(3)));
        assertThat(takeover.claimForExecution(started.runId(), admin)).isTrue();

        await(() -> cancelled.containsKey("挂起题")); // 旧实例令牌校验失败，取消在途题
        await(() -> !owner.hasExecution(started.runId())); // 旧实例静默退出
        assertThat(store.find(started.runId()).orElseThrow().state())
                .as("旧实例不写终态，仍为 RUNNING 等待新实例收尾")
                .isEqualTo(EvalRunState.RUNNING);

        takeover.executeClaimed(started.runId(), admin);

        var done = takeover.status(started.runId());
        assertThat(done.state()).isEqualTo(EvalRunState.COMPLETED);
        assertThat(done.completed()).isEqualTo(2);
        assertThat(done.ownerInstanceId()).isEqualTo("instance-b");
    }

    @Test
    void unreportedCheckpointsAreBackfilledWhenRunContinues() throws Exception {
        stub("挂起题", "第一题", "挂起题", "第二题");
        report.failUpload = true;
        var service = service(ctx.instanceA);
        startWorker(service, ctx.instanceA);
        var started = service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-bf", null, false));
        await(() -> store.caseCount(started.runId()) == 2); // 两道快题完成，挂起题在途
        service.cancel(started.runId());
        assertThat(store.reportedCaseIds(started.runId())).as("上报持续失败，无一题标记已上报")
                .isEmpty();

        // 继续：先补报已有检查点（两题一批），再跑剩余题
        report.failUpload = false;
        report.traces.clear();
        report.batches.clear();
        var resumed = service.resume(admin, started.runId());
        assertThat(resumed.state()).isEqualTo(EvalRunState.QUEUED);
        await(() -> service.status(started.runId()).state() == EvalRunState.COMPLETED);

        var done = service.status(started.runId());
        assertThat(done.completed()).isEqualTo(3);
        assertThat(store.reportedCaseIds(started.runId())).containsExactlyInAnyOrder("o-0", "o-1", "o-2");
        assertThat(report.batches).as("补报先于未完成题执行（第一批为已有检查点）").first().isEqualTo(2);
    }

    @Test
    void partialUploadOnlyMarksSuccessfulCasesAndResumeOnlyBackfillsMissing() throws Exception {
        stub("挂起题", "第一题", "第二题", "挂起题");
        report.failTraceIds.add(EvalRunService.traceId("run-p", "o-0"));
        var service = service(ctx.instanceA);
        startWorker(service, ctx.instanceA);
        var started = service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-p", null, false));
        await(() -> store.caseCount(started.runId()) == 2);
        service.cancel(started.runId());
        assertThat(store.reportedCaseIds(started.runId()))
                .as("中断收尾批量上报：o-0 失败、o-1 成功")
                .containsExactly("o-1");

        // 继续：只补报未上报的 o-0，已上报的 o-1 不再上报
        report.failTraceIds.clear();
        report.traces.clear();
        report.batches.clear();
        service.resume(admin, started.runId());
        await(() -> service.status(started.runId()).state() == EvalRunState.COMPLETED);

        var done = service.status(started.runId());
        assertThat(done.state()).isEqualTo(EvalRunState.COMPLETED);
        assertThat(done.reportPartial()).isFalse();
        assertThat(store.reportedCaseIds(started.runId())).containsExactlyInAnyOrder("o-0", "o-1", "o-2");
        assertThat(report.traces).extracting(EvalReportPort.TraceRecord::name)
                .containsExactlyInAnyOrder("eval:o-0", "eval:o-2");
        assertThat(report.batches).as("补报缺失的 o-0，随后收尾上报新完成的 o-2").containsExactly(1, 1);
    }

    @Test
    void backfillFailureKeepsReportPartialForNextResume() throws Exception {
        stub("挂起题", "第一题", "挂起题", "第二题");
        report.failUpload = true;
        var service = service(ctx.instanceA);
        startWorker(service, ctx.instanceA);
        var started = service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-bf2", null, false));
        await(() -> store.caseCount(started.runId()) == 2);
        service.cancel(started.runId());
        assertThat(service.status(started.runId()).state()).isEqualTo(EvalRunState.CANCELLED);

        // 继续时补报仍失败：题目照常执行完，终态 REPORT_PARTIAL，检查点完整
        service.resume(admin, started.runId());
        await(() -> {
            var status = service.status(started.runId());
            return status.completed() == 3 && status.state() == EvalRunState.REPORT_PARTIAL;
        });
        assertThat(store.caseCount(started.runId())).isEqualTo(3);
        assertThat(store.reportedCaseIds(started.runId())).isEmpty();
    }
}
