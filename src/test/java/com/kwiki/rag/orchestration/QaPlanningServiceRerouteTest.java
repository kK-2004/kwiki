package com.kwiki.rag.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwiki.rag.activity.AgenticDebugLogger;
import com.kwiki.rag.quality.QualityAssessment;
import com.kwiki.rag.retrieval.ChildEvidence;
import com.kwiki.rag.retrieval.RetrievalBudgets;
import com.kwiki.rag.routing.RetrievalPlan;
import com.kwiki.rag.tool.AgentPlannerPort;
import com.kwiki.rag.tool.EvidencePool;
import com.kwiki.rag.tool.ToolCall;
import com.kwiki.rag.tool.ToolRegistry;
import com.kwiki.wiki.access.AuthorizationScope;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

class QaPlanningServiceRerouteTest {

    static final class ScriptPlanner implements AgentPlannerPort {
        final List<Request> requests = new ArrayList<>();
        final ArrayDeque<List<ToolCall>> scripted = new ArrayDeque<>();

        @Override
        public List<ToolCall> plan(Request request) {
            requests.add(request);
            return scripted.isEmpty() ? List.of() : scripted.pop();
        }
    }

    static ToolCall call(String id, String name, String json) {
        return new ToolCall(id, name, json, "turn");
    }

    static final String DIRECT = "{\"reason\":\"与知识库无关\"}";
    final ScriptPlanner planner = new ScriptPlanner();
    final QaPlanningService service = new QaPlanningService(planner, new ToolRegistry(), AgenticLimits.defaults(),
            new RetrievalBudgets(50, 40, 8, 3, 24000, Duration.ofSeconds(5)), new AgenticDebugLogger());
    final RunContext run = new RunContext("r", new AuthorizationScope(1, false, Set.of(1L), Map.of(1L, 1L)),
            id -> 1, AgenticLimits.defaults(), Map.of());

    static EvidencePool pool() {
        EvidencePool pool = new EvidencePool("现在几点了");
        pool.mergeChildren(List.of(new ChildEvidence("C1", "P1", 1, "PAGE", 1, 1L, "财报", 0, 4, "营业收入",
                1, 1, 0.5)), 12000);
        pool.useHybridRetrieval();
        return pool;
    }

    static QualityAssessment irrelevantReview() {
        return new QualityAssessment(0.0, 0.0, 1.0, false, List.of(), List.of(), List.of("当前时间"),
                "off-topic", "证据与问题无关");
    }

    QaPlanningService.Input input(boolean canReroute) {
        return new QaPlanningService.Input("现在几点了", pool(), null, List.of(), irrelevantReview(), 2, 3, false,
                true, false, List.of(), run, canReroute);
    }

    @Test
    void answerDirectlyBecomesARerouteDecisionWhenAllowed() {
        planner.scripted.add(List.of(call("a", "answer_directly", DIRECT)));

        var decision = service.plan(input(true));

        assertThat(decision).isInstanceOf(QaPlanningService.Decision.Reroute.class);
        assertThat(((QaPlanningService.Decision.Reroute) decision).reason()).isEqualTo("与知识库无关");
        var request = planner.requests.getFirst();
        assertThat(request.tools()).extracting(ToolRegistry.ToolDefinition::name).contains("answer_directly");
        assertThat(request.observation()).containsEntry("canAnswerDirectly", true);
    }

    @Test
    void theToolIsInvisibleAndRejectedWhenNotAllowedThenPlanningDegrades() {
        planner.scripted.add(List.of(call("a", "answer_directly", DIRECT)));
        planner.scripted.add(List.of(call("b", "answer_directly", DIRECT)));

        var decision = service.plan(input(false));

        assertThat(planner.requests.getFirst().tools()).extracting(ToolRegistry.ToolDefinition::name)
                .doesNotContain("answer_directly");
        assertThat(planner.requests.getFirst().observation()).containsEntry("canAnswerDirectly", false);
        assertThat(planner.requests).as("被拒后带原因重试一次").hasSize(2);
        assertThat(planner.requests.get(1).validationError()).isEqualTo("tool-unavailable");
        assertThat(decision).isInstanceOf(QaPlanningService.Decision.Degrade.class);
    }

    @Test
    void answerDirectlyBatchedWithAnotherToolIsRetriedWithTheReason() {
        planner.scripted.add(List.of(call("a", "answer_directly", DIRECT),
                call("b", "rewrite_query", "{\"mode\":\"FEEDBACK\"}")));
        planner.scripted.add(List.of(call("c", "answer_directly", DIRECT)));

        var decision = service.plan(input(true));

        assertThat(planner.requests.get(1).validationError()).isEqualTo("answer_directly_must_be_alone");
        assertThat(decision).isInstanceOf(QaPlanningService.Decision.Reroute.class);
    }
}
