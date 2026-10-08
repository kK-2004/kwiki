package com.kwiki.rag.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwiki.rag.orchestration.AgenticWorkflowPort;
import com.kwiki.rag.orchestration.RouteInput;
import com.kwiki.rag.routing.Intent;
import com.kwiki.rag.routing.PriorRoute;
import com.kwiki.rag.routing.RouteSource;
import com.kwiki.security.CurrentUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

class AgenticAnswerClarificationTest {

    final CurrentUser user = new CurrentUser(1L, "qa", false);
    final AgenticWorkflowPort workflow = mock(AgenticWorkflowPort.class);
    final ChatSessionService sessions = mock(ChatSessionService.class);
    final ChatRunEventStore runEvents = mock(ChatRunEventStore.class);
    final ChatSessionService.RunHandle run = new ChatSessionService.RunHandle("s", "c", "r", 1L, 2L, false);
    AgenticAnswerService service;

    @BeforeEach
    void setUp() {
        service = new AgenticAnswerService(workflow,
                new ObjectProvider<ChatSessionService>() {
                    public ChatSessionService getObject() { return sessions; }
                    public ChatSessionService getIfAvailable() { return sessions; }
                },
                new ObjectProvider<ChatRunEventStore>() {
                    public ChatRunEventStore getObject() { return runEvents; }
                    public ChatRunEventStore getIfAvailable() { return runEvents; }
                });
        when(sessions.begin(eq(user), any(), eq("c"), any(), anyString())).thenReturn(run);
        when(sessions.historyBefore(2L)).thenReturn(List.of());
    }

    static Map<String, Object> routeMap() {
        return new PriorRoute(Intent.KNOWLEDGE_QA, RouteSource.RULE, true, 1.0).toMap();
    }

    @Test
    void doneRouteIsRecordedAndPriorRouteIsPassedToTheWorkflow() {
        PriorRoute prior = new PriorRoute(Intent.PROCEDURAL, RouteSource.LLM, true, 0.9);
        when(sessions.priorRoute(2L)).thenReturn(prior);
        when(workflow.answerInSession(eq(user), eq("问题"), eq(List.of()), eq(Set.of()), eq(Set.of()),
                any(RouteInput.class))).thenReturn(Flux.just(
                ChatStreamEvent.of("token", 1, "r", Map.of("text", "答案")),
                ChatStreamEvent.of("done", 2, "r", Map.of("outcome", "completed", "route", routeMap()))));

        service.answer(user, "问题", null, "c", null).collectList().block();

        ArgumentCaptor<RouteInput> captor = ArgumentCaptor.forClass(RouteInput.class);
        verify(workflow).answerInSession(eq(user), eq("问题"), eq(List.of()), eq(Set.of()), eq(Set.of()),
                captor.capture());
        assertThat(captor.getValue().prior()).isEqualTo(prior);
        assertThat(captor.getValue().clarificationResumed()).isFalse();
        verify(sessions).recordRoute(2L, new PriorRoute(Intent.KNOWLEDGE_QA, RouteSource.RULE, true, 1.0));
        verify(sessions, never()).savePendingClarification(anyLong(), anyString());
    }

    @Test
    void clarificationIsStoredAndTheQuestionBecomesTheAssistantMessage() {
        Map<String, Object> payload = Map.of(
                "clarificationId", "c-1", "question", "请说明你指的是哪个页面？",
                "options", List.of(), "allowFreeText", true, "source", "router");
        when(workflow.answerInSession(eq(user), eq("它怎么用？"), eq(List.of()), eq(Set.of()), eq(Set.of()),
                any(RouteInput.class))).thenReturn(Flux.just(
                ChatStreamEvent.of("clarification", 1, "r", payload),
                ChatStreamEvent.of("done", 2, "r", Map.of("outcome", "clarification",
                        "clarificationId", "c-1", "route", routeMap()))));

        var events = service.answer(user, "它怎么用？", null, "c", null).collectList().block();

        assertThat(events).extracting(ChatStreamEvent::type)
                .containsExactly("session", "clarification", "done");
        verify(sessions).savePendingClarification(eq(2L), argThat(json ->
                json.contains("\"id\":\"c-1\"") && json.contains("\"originalQuery\":\"它怎么用？\"")));
        verify(sessions).finish(user, run, "它怎么用？", "请说明你指的是哪个页面？", true, null);
    }

    @Test
    void validReplyResumesWithTheComposedQueryAndDisablesFurtherClarification() {
        String stored = PendingClarification.from(Map.of(
                "clarificationId", "c-1", "question", "哪一个？",
                "options", List.of(Map.of("id", "a", "label", "部署文档")),
                "allowFreeText", true, "source", "router"), "介绍一下它").toJson();
        when(sessions.pendingClarification(2L)).thenReturn(stored);
        when(workflow.answerInSession(eq(user), anyString(), eq(List.of()), any(), any(), any(RouteInput.class)))
                .thenReturn(Flux.just(ChatStreamEvent.of("done", 1, "r", Map.of("outcome", "completed"))));

        service.answer(user, "部署文档", null, "c", null, Set.of(), Set.of(),
                new ClarificationReply("c-1", "a", null)).collectList().block();

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RouteInput> input = ArgumentCaptor.forClass(RouteInput.class);
        verify(workflow).answerInSession(eq(user), query.capture(), eq(List.of()), any(), any(), input.capture());
        assertThat(query.getValue()).contains("介绍一下它").contains("部署文档");
        assertThat(input.getValue().clarificationResumed()).isTrue();
        // 用户消息展示的仍是用户点选/输入的原文
        verify(sessions).finish(user, run, "部署文档", "", true, null);
    }

    @Test
    void replyWithAnUnknownIdIsTreatedAsAnOrdinaryQuestion() {
        String stored = PendingClarification.from(Map.of(
                "clarificationId", "c-1", "question", "哪一个？",
                "options", List.of(), "allowFreeText", true, "source", "router"), "介绍一下它").toJson();
        when(sessions.pendingClarification(2L)).thenReturn(stored);
        when(workflow.answerInSession(eq(user), eq("另一个问题"), eq(List.of()), any(), any(), any(RouteInput.class)))
                .thenReturn(Flux.just(ChatStreamEvent.of("done", 1, "r", Map.of("outcome", "completed"))));

        service.answer(user, "另一个问题", null, "c", null, Set.of(), Set.of(),
                new ClarificationReply("stale", null, "随便")).collectList().block();

        ArgumentCaptor<RouteInput> input = ArgumentCaptor.forClass(RouteInput.class);
        verify(workflow).answerInSession(eq(user), eq("另一个问题"), eq(List.of()), any(), any(), input.capture());
        assertThat(input.getValue().clarificationResumed()).isFalse();
    }

    @Test
    void persistenceFailuresNeverBreakTheLiveStream() {
        when(workflow.answerInSession(eq(user), eq("问题"), eq(List.of()), any(), any(), any(RouteInput.class)))
                .thenReturn(Flux.just(ChatStreamEvent.of("done", 1, "r",
                        Map.of("outcome", "completed", "route", routeMap()))));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(sessions).recordRoute(anyLong(), any());

        var events = service.answer(user, "问题", null, "c", null).collectList().block();

        assertThat(events).extracting(ChatStreamEvent::type).containsExactly("session", "done");
    }
}
