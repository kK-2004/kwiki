package com.kwiki.infrastructure.ai;

import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.rag.rewrite.RewriteLlmPort;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class RewriteLlmAdapter implements RewriteLlmPort {
    private final ChatModel model;

    public RewriteLlmAdapter(ChatModel model) {
        this.model = model;
    }

    public Optional<String> complete(String system, String user) {
        try {
            if (RunContext.current() != null) RunContext.current().modelCall();
            String text =
                    model.chat(List.of(SystemMessage.from(system), UserMessage.from(user)))
                            .aiMessage()
                            .text();
            return text == null || text.isBlank() || text.length() > 3000
                    ? Optional.empty()
                    : Optional.of(text);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
