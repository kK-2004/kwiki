package com.kwiki.rag.answer;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.api.CitationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

/** SSE chat stream and authorized citation resolution. */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("isAuthenticated()")
public class ChatController {

    private final AgenticAnswerService answers;
    private final CitationService citations;

    public ChatController(AgenticAnswerService answers, CitationService citations) {
        this.answers = answers;
        this.citations = citations;
    }

    public record ChatRequest(@NotBlank String query,
                              String sessionId,
                              String clientMessageId,
                              String agentId,
                              java.util.Set<Long> knowledgeBaseIds,
                              java.util.Set<Long> pageIds) {
        public ChatRequest(String query) {
            this(query, null, null, null, java.util.Set.of(), java.util.Set.of());
        }
        public ChatRequest(String query, String sessionId, String clientMessageId, String agentId) {
            this(query, sessionId, clientMessageId, agentId, java.util.Set.of(), java.util.Set.of());
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> stream(
            @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody ChatRequest request) {
        Flux<ChatStreamEvent> events = request.sessionId() == null && request.clientMessageId() == null
                ? answers.answer(user, request.query())
                : answers.answer(user, request.query(), request.sessionId(), request.clientMessageId(), request.agentId(),
                        request.knowledgeBaseIds(), request.pageIds());
        return events
                .map(
                        event ->
                                ServerSentEvent.<String>builder()
                                        .event(event.type())
                                        .data(event.toJson())
                                        .build());
    }

    @GetMapping("/citations/{childChunkKey}")
    TransDTO<java.util.Map<String, Object>> citation(
            @AuthenticationPrincipal CurrentUser user, @PathVariable String childChunkKey) {
        return TransDTO.success(citations.resolve(user, childChunkKey));
    }
}
