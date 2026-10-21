package com.kwiki.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import com.kwiki.rag.orchestration.RunObserver;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.QaRetrievalBudgets;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.AuthorizationScope;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvalRunServiceTest {

    final InMemoryEvalRunStore store = new InMemoryEvalRunStore();
    final CurrentUser admin = new CurrentUser(1L, "admin", true);
    final EvalDatasetLoader loader = mock(EvalDatasetLoader.class);
    final BaselineRetrievalProbe baseline = mock(BaselineRetrievalProbe.class);
    final WorkflowProbe workflow = mock(WorkflowProbe.class);
    final AuthorizationScopeResolver scopes = mock(AuthorizationScopeResolver.class);
    final EvalCorpusService corpus = mock(EvalCorpusService.class);
    final InMemoryReport report = new InMemoryReport();

    static ChunkHit hit(String key, String content) {
        return hit(key, 1L, content);
    }

    static ChunkHit hit(String key, long pageId, String content) {
        return new ChunkHit(key, "P" + key, 7, "PAGE", pageId, 1L, "heading", 0, content.length(), content);
    }

    static RunObserver.ContextSegment segment(long pageId, String text) {
        return new RunObserver.ContextSegment("PAGE", pageId, text);
    }

    static EvalCase positive(String id, String query) {
        return new EvalCase(id, "dragonball", 50, "事实性问题", "Finance", query,
                List.of(List.of("10000吨")), false, List.of(5), List.of());
    }

    EvalRunService service() {
        when(scopes.resolve(eq(admin), eq(Set.of(7L)), eq(Set.of())))
                .thenReturn(new AuthorizationScope(1L, false, Set.of(7L), Map.of()));
        return new EvalRunService(loader, baseline, workflow, scopes, corpus, report,
                store,
                new KwikiDistributedLocks(StandardTestProperties.nullProvider()),
                new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 12000, 24000, 24000),
                2, List.of("无法回答", "没有相关信息"), Clock.systemUTC());
    }

    void stubCases() {
        when(corpus.pageDocIds(admin, 7L, EvalCorpusService.DEFAULT_CORPUS)).thenReturn(Map.of(1L, 5));
        var hit = hit("C1", "排放量为10,000吨");
        when(loader.load()).thenReturn(List.of(
                positive("p-1", "排放量是多少"),
                positive("p-err", "会失败的问题"),
                new EvalCase("n-1", "dragonball", 50, "无关无解问", "Finance", "设立了什么子公司",
                        List.of(), true, List.of(5), List.of())));
        when(baseline.probe(eq("排放量是多少"), any())).thenReturn(new BaselineRetrievalProbe.Result(
                List.of(hit), List.of(), List.of(hit), List.of(hit), List.of()));
        when(baseline.probe(eq("会失败的问题"), any())).thenThrow(new IllegalStateException("es down"));
        when(baseline.probe(eq("设立了什么子公司"), any())).thenReturn(new BaselineRetrievalProbe.Result(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        when(workflow.run(eq(admin), eq(7L), eq("排放量是多少"), any(Duration.class))).thenReturn(
                new WorkflowProbe.Result("completed", null, "排放量为1万吨", "排放量为10,000吨",
                        List.of(segment(1L, "排放量为10,000吨")), List.of()));
        when(workflow.run(eq(admin), eq(7L), eq("设立了什么子公司"), any(Duration.class))).thenReturn(
                new WorkflowProbe.Result("insufficient", null, "知识库中没有相关信息，我无法进行回答", "",
                        List.of(), List.of()));
    }

    @Test
    void runScoresReportsAndStoresReport() throws Exception {
        stubCases();
        var service = service();

        var status = service.runNow(admin, new EvalRunService.StartRequest("50q", 7L, "run-a", "基线", false));

        assertThat(status.state()).isEqualTo(EvalRunState.COMPLETED);
        assertThat(status.total()).isEqualTo(3);
        assertThat(status.completed()).isEqualTo(3);
        EvalSummary summary = status.summary();
        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.scored()).isEqualTo(1);
        assertThat(summary.overall().get("pool@50").fullHit()).isEqualTo(1.0);
        assertThat(summary.overall().get("answer").fullHit()).isEqualTo(0.0); // 回答写成「1万吨」，未命中别名
        assertThat(summary.lossMatrix().get("事实性问题")).containsEntry("GENERATION_MISS", 1);
        assertThat(summary.refusalAccuracy()).isEqualTo(1.0);
        assertThat(report.traces).hasSize(3);
        assertThat(report.traces).extracting(EvalReportPort.TraceRecord::datasetItemId)
                .contains("kwiki-rag-50q:p-1");
        assertThat(report.traces).extracting(EvalReportPort.TraceRecord::runName).containsOnly("run-a");
        assertThat(report.flushes).isEqualTo(1);
        assertThat(store.cases(status.runId())).containsOnlyKeys("p-1", "p-err", "n-1");
        String json = new ObjectMapper().writeValueAsString(service.report(status.runId()));
        assertThat(json).contains("\"runName\":\"run-a\"").contains("p-err").contains("\"summary\"");
    }

    @Test
    void keywordsInNonSourceDocumentsDoNotCountAsHits() throws Exception {
        // 页面 1 → doc 5（本题来源），页面 2 → doc 6（同领域别家公司，模板相同、数字相同）
        when(corpus.pageDocIds(admin, 7L, EvalCorpusService.DEFAULT_CORPUS)).thenReturn(Map.of(1L, 5, 2L, 6));
        when(loader.load()).thenReturn(List.of(positive("p-1", "排放量是多少")));
        var source = hit("C1", 1L, "本公司披露了年度环境信息");
        var other = hit("C2", 2L, "排放量为10,000吨");
        when(baseline.probe(eq("排放量是多少"), any())).thenReturn(new BaselineRetrievalProbe.Result(
                List.of(source, other), List.of(other), List.of(source, other), List.of(source, other),
                List.of()));
        when(workflow.run(eq(admin), eq(7L), eq("排放量是多少"), any(Duration.class))).thenReturn(
                new WorkflowProbe.Result("completed", null, "排放量为10000吨", "本公司披露了年度环境信息\n排放量为10,000吨",
                        List.of(segment(1L, "本公司披露了年度环境信息"), segment(2L, "排放量为10,000吨")),
                        List.of()));

        var service = service();
        var status = service.runNow(admin, new EvalRunService.StartRequest("50q", 7L, "run-src", null, false));

        EvalSummary summary = status.summary();
        assertThat(summary.overall().get("bm25@50").coverage()).isZero();
        assertThat(summary.overall().get("pool@50").coverage()).isZero();
        assertThat(summary.overall().get("fused@20").coverage()).isZero();
        assertThat(summary.overall().get("context").coverage()).isZero();
        assertThat(summary.overall().get("answer").coverage()).as("回答层仍按纯文本匹配").isEqualTo(1.0);
        var trace = report.traces.getFirst();
        assertThat(trace.spans()).filteredOn(span -> span.name().equals("pool@50")).singleElement()
                .satisfies(span -> assertThat(span.output()).containsEntry("nonSourceFiltered", 1));
        assertThat(trace.spans()).filteredOn(span -> span.name().equals("context")).singleElement()
                .satisfies(span -> assertThat(span.output()).containsEntry("nonSourceFiltered", 1)
                        .containsEntry("matched", List.of()));
        assertThat(trace.spans()).filteredOn(span -> span.name().equals("answer")).singleElement()
                .satisfies(span -> assertThat(span.output()).doesNotContainKey("nonSourceFiltered"));
        assertThat(new ObjectMapper().writeValueAsString(service.report(status.runId())))
                .contains("\"nonSourceFiltered\"");
    }

    @Test
    void customCasesWithoutSourceDocsMatchAnyChunk() {
        var evalCase = new EvalCase("c-1", "custom", 50, "事实性问题", "", "排放量是多少",
                List.of(List.of("10000吨")), false, List.of(), List.of());
        var other = hit("C2", 2L, "排放量为10,000吨");
        when(baseline.probe(eq("排放量是多少"), any())).thenReturn(new BaselineRetrievalProbe.Result(
                List.of(other), List.of(), List.of(other), List.of(other), List.of()));
        when(workflow.run(eq(admin), eq(7L), eq("排放量是多少"), any(Duration.class))).thenReturn(
                new WorkflowProbe.Result("completed", null, "", "排放量为10,000吨",
                        List.of(segment(2L, "排放量为10,000吨")), List.of()));

        var result = service().evaluate(admin, 7L, null, Map.of(), evalCase);

        assertThat(result.score().matched(EvalLayer.POOL_50)).containsExactly(0);
        assertThat(result.score().matched(EvalLayer.CONTEXT)).containsExactly(0);
        assertThat(result.nonSourceFiltered()).containsEntry(EvalLayer.BM25_50, 0)
                .containsEntry(EvalLayer.CONTEXT, 0);
    }

    @Test
    void localOnlyRunNeverTouchesLangfuse() {
        stubCases();
        report.available = false;

        var status = service().runNow(admin, new EvalRunService.StartRequest("50q", 7L, null, null, true));

        assertThat(status.state()).isEqualTo(EvalRunState.COMPLETED);
        assertThat(status.runName()).startsWith("50q-");
        assertThat(report.traces).isEmpty();
        assertThat(report.flushes).isZero();
    }

    @Test
    void langfuseFailureMarksReportPartialButKeepsStoredResults() {
        stubCases();
        report.failFlush = true;

        var status = service().runNow(admin, new EvalRunService.StartRequest("50q", 7L, "run-b", null, false));

        assertThat(status.state()).isEqualTo(EvalRunState.REPORT_PARTIAL);
        assertThat(status.reportPartial()).isTrue();
        assertThat(store.caseCount(status.runId())).isEqualTo(3);
    }

    @Test
    void refusesWithoutCredentialsUnlessLocalOnly() {
        report.available = false;
        assertThatThrownBy(() -> service().start(admin, new EvalRunService.StartRequest("50q", 7L, null, null, false)))
                .isInstanceOf(EvalException.class).hasMessageContaining("langfuse-not-configured");
    }

    @Test
    void rejectsUnknownDatasetAndMissingKb() {
        assertThatThrownBy(() -> service().start(admin, new EvalRunService.StartRequest("300q", 7L, null, null, true)))
                .isInstanceOf(EvalException.class);
        assertThatThrownBy(() -> service().start(admin, new EvalRunService.StartRequest("50q", null, null, null, true)))
                .isInstanceOf(EvalException.class).hasMessageContaining("kbId-required");
    }

    @Test
    void secondRunIsRejectedWhileOneIsInProgress() throws Exception {
        stubCases();
        var gate = new CountDownLatch(1);
        when(workflow.run(eq(admin), eq(7L), eq("排放量是多少"), any(Duration.class))).thenAnswer(invocation -> {
            gate.await(5, TimeUnit.SECONDS);
            return new WorkflowProbe.Result("completed", null, "", "", List.of(), List.of());
        });
        var service = service();

        var first = service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-c", null, true));
        assertThat(first.state()).isEqualTo(EvalRunState.RUNNING);
        assertThatThrownBy(() -> service.start(admin, new EvalRunService.StartRequest("50q", 7L, "run-d", null, true)))
                .isInstanceOf(EvalException.class).hasMessageContaining("eval-busy");
        gate.countDown();
        for (int i = 0; i < 50 && service.status(first.runId()).state() == EvalRunState.RUNNING; i++) {
            Thread.sleep(100);
        }
        assertThat(service.status(first.runId()).state()).isEqualTo(EvalRunState.COMPLETED);
    }

    @Test
    void corpusNotReadyBlocksTheRun() {
        when(loader.load()).thenReturn(List.of(positive("p-1", "排放量是多少")));
        org.mockito.Mockito.doThrow(new EvalException(409, "corpus-not-ready: 1 pages not indexed"))
                .when(corpus).requireReady(admin, 7L);
        assertThatThrownBy(() -> service().start(admin, new EvalRunService.StartRequest("50q", 7L, null, null, true)))
                .hasMessageContaining("corpus-not-ready");
    }

    @Test
    void unknownRunIdIsNotFound() {
        assertThatThrownBy(() -> service().status("nope")).isInstanceOf(EvalException.class)
                .hasMessageContaining("eval-run-not-found");
    }
}
