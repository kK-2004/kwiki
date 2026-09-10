package com.kwiki.rag.orchestration;

import com.kwiki.rag.answer.ChatStreamEvent;
import com.kwiki.security.CurrentUser;
import com.kwiki.rag.rewrite.ChatTurn;

import reactor.core.publisher.Flux;

public interface AgenticWorkflowPort {
    Flux<ChatStreamEvent> answer(CurrentUser user, String query);

    /** 可选的持久化对话上下文。旧版适配器可继续使用「仅查询」路径。 */
    default Flux<ChatStreamEvent> answer(CurrentUser user, String query, java.util.List<ChatTurn> history) {
        return answer(user, query);
    }

    /** 该路径的对话持久化由 ChatSessionService 负责。 */
    default Flux<ChatStreamEvent> answerInSession(CurrentUser user, String query, java.util.List<ChatTurn> history) {
        return answer(user, query, history);
    }

    default Flux<ChatStreamEvent> answerInSession(CurrentUser user, String query, java.util.List<ChatTurn> history,
                                                   java.util.Set<Long> kbIds, java.util.Set<Long> pageIds) {
        return answerInSession(user, query, history);
    }
}
