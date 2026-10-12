package com.kwiki.infrastructure.ai;

import com.kwiki.infrastructure.ai.registry.ModelPurpose;
import com.kwiki.infrastructure.ai.registry.ModelRegistry;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.rag.rewrite.RewriteLlmPort;

import dev.langchain4j.data.message.*;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class RewriteLlmAdapter implements RewriteLlmPort {
    private final ModelRegistry models;

    public RewriteLlmAdapter(ModelRegistry models) {
        this.models = models;
    }

    public Optional<String> complete(String system, String user) {
        try {
            if (RunContext.current() != null) RunContext.current().modelCall();
            String text =
                    models.chat(ModelPurpose.REWRITE).chat(List.of(SystemMessage.from(system), UserMessage.from(user)))
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
