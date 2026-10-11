package com.kwiki.rag.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwiki.rag.activity.AgenticDebugLogger;
import com.kwiki.rag.answer.ParentEvidence;
import com.kwiki.rag.quality.QualityAssessment;
import com.kwiki.rag.retrieval.ChildEvidence;
import com.kwiki.rag.retrieval.RetrievalBudgets;
import com.kwiki.rag.rewrite.ChatTurn;
import com.kwiki.rag.routing.Intent;
import com.kwiki.rag.routing.RetrievalPlan;
import com.kwiki.rag.tool.AgentPlannerPort;
import com.kwiki.rag.tool.AgentToolArguments;
import com.kwiki.rag.tool.EvidencePool;
import com.kwiki.rag.tool.RetrievalPlannerPort;
import com.kwiki.rag.tool.ToolCall;
import com.kwiki.rag.tool.ToolRegistry;
import com.kwiki.rag.tool.ToolResult;
import com.kwiki.wiki.access.AuthorizationScope;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

class QaPlanningServiceTest {

    /** 按脚本返回 tool_calls 或抛异常的假 planner，并记录每次请求。 */
    static final class ScriptPlanner implements AgentPlannerPort {
        final List<Request> requests = new ArrayList<>();
        final ArrayDeque<Object> scripted = new ArrayDeque<>();

        @SuppressWarnings("unchecked")
        @Override
        public List<ToolCall> plan(Request request) {
            requests.add(request);
            Object next = scripted.isEmpty() ? List.of() : scripted.pop();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return (List<ToolCall>) next;
        }
    }

    static ToolCall call(String id, String name, String json) {
        return new ToolCall(id, name, json, "turn");
    }

    static final String SEARCH = "{\"queries\":[\"部署\"],\"strategy\":\"HYBRID\",\"range\":\"BASE\"}";

    final ScriptPlanner planner = new ScriptPlanner();
    final ToolRegistry registry = new ToolRegistry();
    final QaPlanningService service = new QaPlanningService(planner, registry, AgenticLimits.defaults(),
            new RetrievalBudgets(50, 40, 8, 3, 24000, Duration.ofSeconds(5)), new AgenticDebugLogger());
    final RunContext run = new RunContext("r", new AuthorizationScope(1, false, Set.of(1L), Map.of(1L, 1L)),
            id -> 1, AgenticLimits.defaults(), Map.of());

    static ChildEvidence child(String key, String content, double score) {
        return new ChildEvidence(key, "P-" + key, 1, "PAGE", 1, 1L, "部署 > 步骤", 0, content.length(), content,
                1, 1, score);
    }

    QaPlanningService.Input input(EvidencePool pool, QualityAssessment review, boolean unexamined, boolean canAsk) {
        return new QaPlanningService.Input("如何部署 kwiki", pool, RetrievalPlan.rule(Intent.PROCEDURAL, "v1",
                List.of("procedural-zh")), List.of(new ChatTurn("USER", "上一条问题")), review, 1, 3, true, canAsk,
                unexamined, List.of(), run);
    }

    QaPlanningService.Input first() {
        return input(new EvidencePool("如何部署 kwiki"), null, true, true);
    }

    static QualityAssessment failedReview() {
        return new QualityAssessment(0.4, 0.5, 0.9, false, List.of(), List.of("无依据陈述"),
                List.of("回滚步骤"), "coverage-missing", "覆盖不足");
    }

    @Test
    void validPlanBecomesTypedCallsAndSeesOnlyTheToolsThatMakeSenseNow() {
        planner.scripted.add(List.of(call("a", "search_chunks", SEARCH)));

        var decision = service.plan(first());

        var calls = ((QaPlanningService.Decision.Calls) decision).calls();
        assertThat(calls).hasSize(1);
        assertThat(((AgentToolArguments.SearchChunks) calls.getFirst().arguments()).queries()).containsExactly("部署");
        var request = planner.requests.getFirst();
        assertThat(request.tools()).extracting(ToolRegistry.ToolDefinition::name)
                .containsExactly("search_chunks", "rewrite_query");
        assertThat(request.maxCalls()).isEqualTo(3);
        assertThat(request.validationError()).isEmpty();
    }

    @Test
    void observationIsBoundedRedactedAndCarriesTheReviewAndBudgets() {
        EvidencePool pool = new EvidencePool("如何部署 kwiki");
        pool.mergeChildren(List.of(child("C1", "步骤一 ".repeat(100) + " sk-abcdefghijklmnop", 0.9),
                child("C2", "短内容", 0.5)), 12000);
        pool.applyRewrite("kwiki 部署 配置");
        pool.useHybridRetrieval();
        planner.scripted.add(List.of(call("a", "search_chunks", SEARCH)));

        service.plan(input(pool, failedReview(), true, true));

        var observation = planner.requests.getFirst().observation();
        assertThat(observation).containsEntry("question", "如何部署 kwiki")
                .containsEntry("currentQuery", "kwiki 部署 配置")
                .containsEntry("round", 1).containsEntry("maxRounds", 3)
                .containsEntry("canAskUser", true).containsEntry("evidenceUnexamined", true);
        assertThat(observation.get("note").toString()).contains("untrusted");
        @SuppressWarnings("unchecked")
        var poolView = (Map<String, Object>) observation.get("pool");
        assertThat(poolView).containsEntry("childCount", 2).containsEntry("parentCount", 0)
                .containsEntry("uncoveredChildren", 2).containsEntry("answerEvidence", "CHILD");
        @SuppressWarnings("unchecked")
        var children = (List<Map<String, Object>>) poolView.get("children");
        assertThat(children.getFirst()).containsEntry("chunkKey", "C1").containsEntry("headingPath", "部署 > 步骤")
                .containsEntry("hasParent", false);
        String excerpt = children.getFirst().get("excerpt").toString();
        assertThat(excerpt.length()).isLessThanOrEqualTo(121);
        assertThat(excerpt).doesNotContain("sk-abcdefghijklmnop");
        @SuppressWarnings("unchecked")
        var review = (Map<String, Object>) observation.get("lastReview");
        assertThat(review).containsEntry("reasonCode", "coverage-missing");
        assertThat(review.get("missingAspects").toString()).contains("回滚步骤");
        @SuppressWarnings("unchecked")
        var budgets = (Map<String, Object>) observation.get("budgetsLeft");
        assertThat(budgets).containsEntry("retrievals", 5).containsEntry("parentFetches", 6)
                .containsEntry("rewrites", 2).containsEntry("graphAvailable", true);
        assertThat(observation.get("recentUserMessages").toString()).contains("上一条问题");
    }

    @Test
    void parentsAreReportedAsTheAnswerEvidenceAndCoverTheirChildren() {
        EvidencePool pool = new EvidencePool("q");
        pool.mergeChildren(List.of(child("C1", "内容一", 0.9), child("C2", "内容二", 0.5)), 12000);
        pool.addParents(List.of(new ParentEvidence("P-C1", "PAGE", 1, 1L, 1, "部署", "正文", false, 0, List.of())));
        planner.scripted.add(List.of(call("a", "search_chunks", SEARCH)));

        service.plan(input(pool, null, true, false));

        @SuppressWarnings("unchecked")
        var poolView = (Map<String, Object>) planner.requests.getFirst().observation().get("pool");
        assertThat(poolView).containsEntry("answerEvidence", "PARENT").containsEntry("uncoveredChildren", 1);
        assertThat(planner.requests.getFirst().tools()).extracting(ToolRegistry.ToolDefinition::name)
                .contains("expand_parents").doesNotContain("ask_user");
    }

    @Test
    void anEmptyPlanMeansSufficientOnlyWhileThereIsUnexaminedEvidence() {
        EvidencePool pool = new EvidencePool("q");
        pool.mergeChildren(List.of(child("C1", "内容", 0.9)), 12000);
        planner.scripted.add(List.of());

        assertThat(service.plan(input(pool, null, true, true))).isInstanceOf(QaPlanningService.Decision.Sufficient.class);

        planner.scripted.add(List.of());
        planner.scripted.add(List.of());
        var decision = service.plan(input(pool, failedReview(), false, true));

        assertThat(decision).isInstanceOf(QaPlanningService.Decision.Degrade.class);
        assertThat(((QaPlanningService.Decision.Degrade) decision).reason())
                .isEqualTo("planner-invalid-output:no-tool-calls-without-new-evidence");
        assertThat(planner.requests.get(2).validationError()).isEqualTo("no-tool-calls-without-new-evidence");
    }

    @Test
    void rejectedOutputIsRetriedOnceWithTheReasonAndThenAccepted() {
        planner.scripted.add(List.of(call("a", "delete_everything", "{}")));
        planner.scripted.add(List.of(call("b", "search_chunks", SEARCH)));

        var decision = service.plan(first());

        assertThat(decision).isInstanceOf(QaPlanningService.Decision.Calls.class);
        assertThat(planner.requests).hasSize(2);
        assertThat(planner.requests.get(1).validationError()).isEqualTo("unknown-tool");
    }

    @Test
    void outputRejectedTwiceDegrades() {
        planner.scripted.add(List.of(call("a", "search_chunks", "{\"queries\":[]}")));
        planner.scripted.add(List.of(call("b", "ask_user", "{\"question\":\"q\"}")));

        var decision = service.plan(first());

        assertThat(decision).isEqualTo(new QaPlanningService.Decision.Degrade("planner-invalid-output:tool-unavailable"));
        assertThat(planner.requests).hasSize(2);
    }

    @Test
    void envelopeErrorsFromTheAdapterAreTreatedAsRejectedOutput() {
        planner.scripted.add(new RunFailure("invalid-tool-envelope"));
        planner.scripted.add(List.of(call("a", "search_chunks", SEARCH)));

        assertThat(service.plan(first())).isInstanceOf(QaPlanningService.Decision.Calls.class);
        assertThat(planner.requests.get(1).validationError()).isEqualTo("invalid-tool-envelope");
    }

    @Test
    void providerFailureDegradesWithoutRetry() {
        planner.scripted.add(new IllegalStateException("provider down"));

        assertThat(service.plan(first())).isEqualTo(new QaPlanningService.Decision.Degrade("planner-unavailable"));
        assertThat(planner.requests).hasSize(1);
    }

    @Test
    void abortsAreNeverSwallowedAsDegradation() {
        planner.scripted.add(new RunFailure("timeout"));
        assertThatThrownBy(() -> service.plan(first())).isInstanceOf(RunFailure.class).hasMessage("timeout");

        planner.scripted.add(new RunFailure("model-budget-exhausted"));
        assertThatThrownBy(() -> service.plan(first())).isInstanceOf(RunFailure.class)
                .hasMessage("model-budget-exhausted");
    }

    @Test
    void askUserMustBeAloneAndOnlyWhenAllowed() {
        EvidencePool pool = new EvidencePool("q");
        pool.mergeChildren(List.of(child("C1", "内容", 0.9)), 12000);
        String ask = "{\"question\":\"你想了解哪一个？\",\"options\":[{\"ref\":\"C1\",\"label\":\"甲\"}]}";
        planner.scripted.add(List.of(call("a", "ask_user", ask), call("b", "search_chunks", SEARCH)));
        planner.scripted.add(List.of(call("c", "ask_user", ask)));

        var decision = service.plan(input(pool, failedReview(), true, true));

        assertThat(decision).isInstanceOf(QaPlanningService.Decision.Calls.class);
        assertThat(planner.requests.get(1).validationError()).isEqualTo("ask_user_must_be_alone");
        assertThat(planner.requests.getFirst().tools()).extracting(ToolRegistry.ToolDefinition::name).contains("ask_user");
    }

    @Test
    void callsBeyondTheRemainingToolBudgetAreRejected() {
        var limits = new AgenticLimits(3, 2, 32, 1, 3, 128, Duration.ofSeconds(300), 256, 16, 6, 6, 12, 12,
                Duration.ofSeconds(30));
        var limitedRun = new RunContext("r", new AuthorizationScope(1, false, Set.of(1L), Map.of(1L, 1L)),
                id -> 1, limits, Map.of());
        var input = new QaPlanningService.Input("q", new EvidencePool("q"), null, List.of(), null, 1, 3, false, false,
                true, List.of(), limitedRun);
        planner.scripted.add(List.of(call("a", "search_chunks", SEARCH), call("b", "rewrite_query", "{\"mode\":\"EXPANSION\"}")));
        planner.scripted.add(List.of(call("c", "search_chunks", SEARCH)));

        var decision = service.plan(input);

        assertThat(decision).isInstanceOf(QaPlanningService.Decision.Calls.class);
        assertThat(planner.requests.get(1).validationError()).isEqualTo("tool-budget-exceeded");
    }

    @Test
    void toolHistoryIsForwardedToThePlanner() {
        var history = List.of(new RetrievalPlannerPort.ToolExchange(call("a", "search_chunks", SEARCH),
                new ToolResult("a", "SUCCESS", List.of("C1"), List.of(), null, Map.of("addedCount", 1))));
        var input = new QaPlanningService.Input("q", new EvidencePool("q"), null, List.of(), null, 2, 3, false, false,
                true, history, run);
        planner.scripted.add(List.of(call("b", "search_chunks", SEARCH)));

        service.plan(input);

        assertThat(planner.requests.getFirst().history()).hasSize(1);
    }
}
