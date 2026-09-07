package com.kwiki.rag.orchestration;

import com.kwiki.rag.answer.ChatStreamEvent;
import com.kwiki.security.CurrentUser;

import reactor.core.publisher.Flux;

public interface AgenticWorkflowPort {
    Flux<ChatStreamEvent> answer(CurrentUser user, String query);
}
