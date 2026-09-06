package com.kwiki.infrastructure.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.infrastructure.observability.SecretRedaction;
import com.kwiki.rag.routing.RouterLlmPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Answer-provider-backed router LLM adapter replacing the unfinished k-Rag
 * placeholder. Sends a closed-schema prompt, enforces a deadline, and parses the
 * JSON body; timeout, invalid JSON, and provider errors return empty so the
 * router falls back deterministically. Logs are sanitized: only the provider
 * name and correlation id, never the credential.
 */
@Component
public class RouterLlmAdapter implements RouterLlmPort {

    private static final Logger log = LoggerFactory.getLogger(RouterLlmAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final String model;
    private final Duration deadline;

    public RouterLlmAdapter(@Qualifier("kwikiAnswerLlmWebClient") WebClient webClient,
                            com.kwiki.infrastructure.config.ExternalServicesProperties properties,
                            @Value("${kwiki.routing.deadline:10s}") Duration deadline) {
        this.webClient = webClient;
        this.model = properties.answerLlm().model();
        this.deadline = deadline;
    }

    @Override
    public Optional<Map<String, Object>> askRouter(String normalizedQuery, String matchTrace) {
        try {
            String content = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(Map.of(
                            "model", model,
                            "temperature", 0.0,
                            "messages", new Object[]{
                                    Map.of("role", "system", "content", systemPrompt()),
                                    Map.of("role", "user",
                                            "content", "Query: " + normalizedQuery
                                                    + "\nRule trace: " + matchTrace)}))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(deadline);
            return parseContentJson(content);
        } catch (Exception e) {
            log.warn("router llm call failed: {} (traceId={})",
                    e.getClass().getSimpleName(),
                    org.slf4j.MDC.get("traceId"));
            return Optional.empty();
        }
    }

    private static Optional<Map<String, Object>> parseContentJson(String content) {
        try {
            if (content == null) {
                return Optional.empty();
            }
            Map<String, Object> completion = MAPPER.readValue(content,
                    new TypeReference<Map<String, Object>>() {
                    });
            Object choices = completion.get("choices");
            if (choices instanceof java.util.List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> first
                    && first.get("message") instanceof Map<?, ?> message) {
                Object raw = message.get("content");
                String json = String.valueOf(raw).strip();
                // tolerate markdown fences some providers add
                if (json.startsWith("```")) {
                    json = json.replaceFirst("^```[a-z]*\\n?", "").replaceFirst("```$", "").strip();
                }
                Map<String, Object> decision = MAPPER.readValue(json,
                        new TypeReference<Map<String, Object>>() {
                        });
                return Optional.of(decision);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("router llm output unparsable: {}", SecretRedaction.redact(
                    String.valueOf(e.getMessage())));
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
