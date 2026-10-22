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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 队列与名额语义（设计 §5 / §13）：提交即排队；本实例名额满不认领；集群名额上限排队、
 * 释放后认领；已取消的排队任务被跳过；提交人失去管理员权限时失败。
 */
class EvalRunQueueTest {

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

    @AfterEach
    void stopWorkers() {
        workers.forEach(EvalWorker::stop);
        workers.clear();
    }

    static EvalCase positive(String id, String query) {
        return new EvalCase(id, "dragonball", 50, "事实性问题", "Finance", query,
                List.of(List.of("10000吨")), false, List.of(5), List.of());
    }

    /** 慢题（「慢」开头）订阅后挂起直到取消，其余立即完成。 */
    void stub(String... queries) {
        when(corpus.pageDocIds(admin, 7L, EvalCorpusService.DEFAULT_CORPUS)).thenReturn(Map.of(1L, 5));
        List<EvalCase> cases = new java.util.ArrayList<>();
        for (int i = 0; i < queries.length; i++) cases.add(positive("q-" + i, queries[i]));
        when(loader.load()).thenReturn(cases);
        when(baseline.probe(anyString(), any())).thenReturn(new BaselineRetrievalProbe.Result(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        when(agent.answerInSession(eq(admin), anyString(), anyList(), eq(Set.of(7L)), eq(Set.of()),
                eq(RouteInput.NONE), any())).thenAnswer(invocation -> {
                    String query = invocation.getArgument(1);
                    if (!query.startsWith("慢")) {
                        return Flux.just(ChatStreamEvent.of("token", 1, "r", Map.of("text", "10000吨")),
                                ChatStreamEvent.of("done", 2, "r", Map.of("outcome", "completed")));
                    }
                    return Flux.<ChatStreamEvent>never()
                            .doOnSubscribe(s -> subscribed.computeIfAbsent(query, q -> new CountDownLatch(1))
                                    .countDown())
                            .doOnCancel(() -> cancelled.computeIfAbsent(query, q -> new AtomicBoolean()).set(true));
                });
    }

    EvalRunService service(EvalInstanceId instance, int maxConcurrentRuns) {
        when(scopes.resolve(eq(admin), eq(Set.of(7L)), eq(Set.of())))
                .thenReturn(new AuthorizationScope(1L, false, Set.of(7L), Map.of()));
        return ctx.service(loader, baseline, new WorkflowProbe(agent), scopes, corpus, report,
                new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 12000, 24000, 24000),
                instance, 2, maxConcurrentRuns, List.of("无法回答"), Clock.systemUTC());
    }

    /** 为已构造好的服务启动认领线程（20ms 轮询），测毕统一停止。 */
    EvalWorker startWorker(EvalRunService service, EvalInstanceId instance, int maxConcurrentRuns) {
        EvalWorker worker = ctx.worker(service, instance, maxConcurrentRuns, Duration.ofMillis(20));
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
    void startQueuesAndWorkerClaimsThenCompletes() throws Exception {
        stub("快题");
        var service = service(ctx.instanceA, 2);
        startWorker(service, ctx.instanceA, 2);

        var status = service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-1", null, true));

        assertThat(status.state()).isEqualTo(EvalRunState.QUEUED);
        await(() -> service.status(status.runId()).state() == EvalRunState.COMPLETED);
        var done = service.status(status.runId());
        assertThat(done.ownerInstanceId()).isEqualTo("instance-a");
        assertThat(done.queuePosition()).isNull();
        assertThat(store.caseCount(status.runId())).isEqualTo(1);
    }

    @Test
    void secondRunWaitsUntilFirstFinishesOnTheSameInstance() throws Exception {
        stub("慢题", "快题");
        var service = service(ctx.instanceA, 2);
        startWorker(service, ctx.instanceA, 2);

        var first = service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-a", null, true));
        var second = service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-b", null, true));
        assertThat(subscribed("慢题").await(5, TimeUnit.SECONDS)).isTrue();
        await(() -> store.caseCount(first.runId()) == 1);

        assertThat(service.status(second.runId()).state()).as("本实例名额已满，第二个 run 继续排队")
                .isEqualTo(EvalRunState.QUEUED);
        assertThat(service.status(second.runId()).queuePosition()).as("第一个 run 已被认领出队").isZero();

        service.cancel(first.runId());
        // 名额释放后 run-b 被认领执行（其中的慢题再次挂起，证明确实在执行）
        await(() -> service.status(second.runId()).state() == EvalRunState.RUNNING);
        service.cancel(second.runId());
        await(() -> service.status(second.runId()).state() == EvalRunState.CANCELLED);
        await(() -> cancelled.containsKey("慢题"));
    }

    @Test
    void clusterSlotLimitKeepsRunQueuedUntilAnotherInstanceReleases() throws Exception {
        stub("慢题", "快题");
        // 两个实例共享 store / queue / slots / bus / run 锁，集群名额上限 1
        var serviceA = service(ctx.instanceA, 1);
        var serviceB = service(ctx.instanceB, 1);
        EvalWorker workerA = startWorker(serviceA, ctx.instanceA, 1);
        startWorker(serviceB, ctx.instanceB, 1);

        var onA = serviceA.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-a", null, true));
        await(() -> serviceA.status(onA.runId()).state() == EvalRunState.RUNNING);
        var onB = serviceB.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-b", null, true));

        assertThat(serviceB.status(onB.runId()).state()).as("集群名额已满，另一实例的 run 排队")
                .isEqualTo(EvalRunState.QUEUED);

        // A 下线：中断在途执行并释放集群名额，B 认领 run-b（其中的慢题再次挂起，证明确实在执行）
        workerA.stop();
        await(() -> serviceB.status(onB.runId()).state() == EvalRunState.RUNNING);
        assertThat(serviceB.status(onB.runId()).ownerInstanceId()).isEqualTo("instance-b");
        serviceB.cancel(onB.runId());
        await(() -> serviceB.status(onB.runId()).state() == EvalRunState.CANCELLED);
        await(() -> cancelled.containsKey("慢题"));
    }

    @Test
    void cancelledQueuedTaskIsSkippedByTheWorker() throws Exception {
        stub("快题");
        var service = service(ctx.instanceA, 2); // 先不启动认领线程
        var status = service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-q", null, true));
        service.cancel(status.runId());

        startWorker(service, ctx.instanceA, 2);
        Thread.sleep(300); // 给认领线程消费该任务的机会

        assertThat(service.status(status.runId()).state()).isEqualTo(EvalRunState.CANCELLED);
        assertThat(store.caseCount(status.runId())).isZero();
        verify(baseline, times(0)).probe(anyString(), any());
    }

    @Test
    void submitterWhoLostAdminRightsFailsTheRun() throws Exception {
        stub("快题");
        var service = new EvalRunService(loader, baseline, new WorkflowProbe(agent), scopes, corpus, report,
                store, ctx.queue, ctx.slots, ctx.bus, ctx.runLocks,
                new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 12000, 24000, 24000), ctx.instanceA, 2, 2,
                List.of("无法回答"), Clock.systemUTC());
        var submitter = new CurrentUser(2L, "guest", true);
        var status = service.start(submitter, new EvalRunService.StartRequest("50q", 7L, "run-g", null, true));

        startWorker(service, ctx.instanceA, 2);

        await(() -> service.status(status.runId()).state() == EvalRunState.FAILED);
        assertThat(service.status(status.runId()).error()).isEqualTo("submitter-not-authorized");
        assertThat(store.caseCount(status.runId())).isZero();
    }
}
