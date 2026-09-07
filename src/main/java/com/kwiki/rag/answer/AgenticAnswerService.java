package com.kwiki.rag.answer;

import com.kwiki.rag.orchestration.AgenticWorkflowPort;
import com.kwiki.security.CurrentUser;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

@Service
public class AgenticAnswerService {
    private final AgenticWorkflowPort workflow;

    public AgenticAnswerService(AgenticWorkflowPort workflow) {
        this.workflow = workflow;
    }

    public Flux<ChatStreamEvent> answer(CurrentUser user, String query) {
        return workflow.answer(user, query);
    }
}
