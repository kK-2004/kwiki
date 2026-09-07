package com.kwiki.infrastructure.ai;

import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.rag.routing.RouterLlmPort;
import com.kwiki.rag.tool.ToolRegistry;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class RouterLlmAdapter implements RouterLlmPort {
    private final ChatModel model;

    public RouterLlmAdapter(ChatModel model) {
        this.model = model;
    }

    @Override
    public Optional<Map<String, Object>> askRouter(String query, String trace) {
        try {
            if (RunContext.current() != null) RunContext.current().modelCall();
            String text =
                    model.chat(
                                    List.of(
                                            SystemMessage.from(systemPrompt()),
                                            UserMessage.from(
                                                    "Query: " + query + "\nRule trace: " + trace)))
                            .aiMessage()
                            .text();
            if (text.startsWith("```"))
                text = text.replaceFirst("^```[a-z]*\\n?", "").replaceFirst("```$", "").strip();
            Map<String, Object> result =
                    ToolRegistry.JSON.convertValue(ToolRegistry.parse(text), Map.class);
            com.kwiki.rag.routing.RouterDecisionValidator.validate(result);
            return Optional.of(result);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static String systemPrompt() {
        return """
               You classify queries for an enterprise wiki retrieval system.
               Respond with ONLY a JSON object matching exactly this schema:
               {"intent":"DIRECT_ANSWER|KNOWLEDGE_QA|PROCEDURAL|ANALYTICAL",
                "needsRetrieval":true|false,
                "rewriteMode":"NONE|CONVERSATIONAL|EXPANSION|DECOMPOSITION",
                "subqueries":["at most three short questions"],
                "confidence":0.0}
               Never include Elasticsearch DSL, authorization scopes, credentials,
               graph queries, or any additional field.
               """;
    }
}
