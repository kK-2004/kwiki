package com.kwiki.rag.orchestration;

import com.kwiki.rag.answer.ChatStreamEvent;
import com.kwiki.security.CurrentUser;
import com.kwiki.rag.rewrite.ChatTurn;

import reactor.core.publisher.Flux;

public interface AgenticWorkflowPort {
    Flux<ChatStreamEvent> answer(CurrentUser user, String query);

    /** Optional durable conversation context. Legacy adapters can keep the query-only path. */
    default Flux<ChatStreamEvent> answer(CurrentUser user, String query, java.util.List<ChatTurn> history) {
        return answer(user, query);
    }

    /** Conversation persistence is owned by ChatSessionService for this path. */
    default Flux<ChatStreamEvent> answerInSession(CurrentUser user, String query, java.util.List<ChatTurn> history) {
        return answer(user, query, history);
    }

    default Flux<ChatStreamEvent> answerInSession(CurrentUser user, String query, java.util.List<ChatTurn> history,
                                                   java.util.Set<Long> kbIds, java.util.Set<Long> pageIds) {
        return answerInSession(user, query, history);
    }
}
