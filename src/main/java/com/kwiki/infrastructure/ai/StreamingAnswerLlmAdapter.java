package com.kwiki.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.rag.answer.AnswerLlmPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible streaming chat adapter for cited answers: stream=true SSE
 * parsing, response timeout as deadline, sanitized errors (no credential
 * leakage), and cancellation that propagates to the underlying connection.
 */
@Component
public class StreamingAnswerLlmAdapter implements AnswerLlmPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final String model;
    private final Duration timeout;

    public StreamingAnswerLlmAdapter(@Qualifier("kwikiAnswerLlmWebClient") WebClient webClient,
                                     com.kwiki.infrastructure.config.ExternalServicesProperties properties,
                                     @org.springframework.beans.factory.annotation.Value(
                                             "${kwiki.answer.timeout:180s}") Duration timeout) {
        this.webClient = webClient;
        this.model = properties.answerLlm().model();
        this.timeout = timeout;
    }

    @Override
    public Flux<String> streamAnswer(String prompt) {
        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(Map.of(
                        "model", model,
                        "stream", true,
                        "temperature", 0.2,
                        "messages", List.of(
                                Map.of("role", "system", "content", evidenceOnlyContract()),
                                Map.of("role", "user", "content", prompt))))
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(timeout)
                .map(this::deltaFrom)
                .takeUntil(delta -> delta == null)
                .filter(delta -> delta != null && !delta.isEmpty());
    }

    /** Extracts the delta content of one SSE data line; "[DONE]" terminates. */
    private String deltaFrom(String dataLine) {
        try {
            String payload = dataLine.trim();
            if (payload.isEmpty()) {
                return "";
            }
            if (payload.startsWith("data:")) {
                payload = payload.substring(5).trim();
            }
            if ("[DONE]".equals(payload)) {
                return null;
            }
            Map<String, Object> frame = MAPPER.readValue(payload, Map.class);
            Object choices = frame.get("choices");
            if (choices instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> first
                    && first.get("delta") instanceof Map<?, ?> delta
                    && delta.get("content") instanceof String content) {
                return content;
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    static String evidenceOnlyContract() {
        return """
                Answer ONLY from the provided parent-chunk evidence.
                Distinguish supported statements from missing knowledge explicitly.
                Never invent citations; every factual sentence must map to evidence.
                """;
    }
}
