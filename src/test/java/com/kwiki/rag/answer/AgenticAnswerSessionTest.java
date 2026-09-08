package com.kwiki.rag.answer;

import com.kwiki.rag.orchestration.AgenticWorkflowPort;
import com.kwiki.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class AgenticAnswerSessionTest {
    @Test
    void durableTurnsUseTheSessionPathAndRecordErrorEventsAsFailures() {
        var workflow = mock(AgenticWorkflowPort.class);
        var sessions = mock(ChatSessionService.class);
        ObjectProvider<ChatSessionService> provider = new ObjectProvider<>() {
            public ChatSessionService getObject() { return sessions; }
            public ChatSessionService getIfAvailable() { return sessions; }
        };
        var user = new CurrentUser(1L, "qa", false);
        var run = new ChatSessionService.RunHandle("s", "c", "r", 1L, 2L, false);
        when(sessions.begin(user, null, "c", null, "问题")).thenReturn(run);
        when(sessions.historyBefore(2L)).thenReturn(List.of());
        when(workflow.answerInSession(user, "问题", List.of())).thenReturn(Flux.just(
                ChatStreamEvent.of("error", 1, "r", Map.of("error", "retrieval-failed"))));
        var events = new AgenticAnswerService(workflow, provider).answer(user, "问题", null, "c", null).collectList().block();
        assertThat(events).extracting(ChatStreamEvent::type).containsExactly("session", "error");
        verify(workflow, never()).answer(any(), anyString(), anyList());
        verify(sessions).finish(user, run, "问题", "", false, "answer_failed");
    }
}
