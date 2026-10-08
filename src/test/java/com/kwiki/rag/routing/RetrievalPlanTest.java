package com.kwiki.rag.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class RetrievalPlanTest {

    @Test
    void directAnswerRuleDoesNotNeedRetrieval() {
        RetrievalPlan greeting = RetrievalPlan.rule(Intent.DIRECT_ANSWER, "rules-v1", List.of("greet-zh"));
        RetrievalPlan qa = RetrievalPlan.rule(Intent.KNOWLEDGE_QA, "rules-v1", List.of("knowledge-zh"));

        assertThat(greeting.needsRetrieval()).isFalse();
        assertThat(qa.needsRetrieval()).isTrue();
        assertThat(greeting.needsClarification()).isFalse();
    }

    @Test
    void clarifyRequestIsBoundedAndSerializable() {
        ClarifyRequest request = new ClarifyRequest("你指的是哪一个？",
                List.of(new ClarifyRequest.Option("a", "部署文档"), new ClarifyRequest.Option("b", "运维手册")),
                true, "router");

        Map<String, Object> payload = request.toPayload("c-1");

        assertThat(payload).containsEntry("clarificationId", "c-1")
                .containsEntry("question", "你指的是哪一个？")
                .containsEntry("allowFreeText", true)
                .containsEntry("source", "router");
        assertThat((List<?>) payload.get("options")).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) payload.get("options")).get(0);
        assertThat(first).containsEntry("id", "a").containsEntry("label", "部署文档")
                .doesNotContainKey("kbId");
    }

    @Test
    void clarifyRequestRejectsInvalidShapes() {
        assertThatThrownBy(() -> new ClarifyRequest(" ", List.of(), true, "router"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClarifyRequest("q", List.of(), false, "router"))
                .isInstanceOf(IllegalArgumentException.class);
        List<ClarifyRequest.Option> five = List.of(
                new ClarifyRequest.Option("a", "a"), new ClarifyRequest.Option("b", "b"),
                new ClarifyRequest.Option("c", "c"), new ClarifyRequest.Option("d", "d"),
                new ClarifyRequest.Option("e", "e"));
        assertThatThrownBy(() -> new ClarifyRequest("q", five, true, "router"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planCanCarryAndDropClarification() {
        ClarifyRequest request = new ClarifyRequest("补充一下？", List.of(), true, "router");
        RetrievalPlan plan = RetrievalPlan.llm(Intent.KNOWLEDGE_QA, true, RewriteMode.NONE, List.of(), 0.3)
                .withClarify(request);

        assertThat(plan.needsClarification()).isTrue();
        assertThat(plan.withoutClarify().needsClarification()).isFalse();

        RetrievalPlan ruleClarify = RetrievalPlan.ruleClarify("anaphora-no-context", request);
        assertThat(ruleClarify.source()).isEqualTo(RouteSource.RULE);
        assertThat(ruleClarify.matchedRuleIds()).containsExactly("anaphora-no-context");
        assertThat(ruleClarify.needsClarification()).isTrue();
    }

    @Test
    void priorRouteRoundTripsThroughMap() {
        RetrievalPlan plan = RetrievalPlan.rule(Intent.PROCEDURAL, "rules-v1", List.of("procedural-zh"));
        PriorRoute prior = PriorRoute.of(plan);

        assertThat(PriorRoute.fromMap(prior.toMap())).isEqualTo(prior);
        assertThat(PriorRoute.fromMap(Map.of("intent", "BOGUS"))).isNull();
        assertThat(PriorRoute.fromMap(Map.of())).isNull();
    }
}
