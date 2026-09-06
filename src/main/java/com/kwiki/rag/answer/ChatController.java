package com.kwiki.rag.answer;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.api.CitationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.codec.ServerSentEvent;
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

    public record ChatRequest(@NotBlank String query) {
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> stream(@AuthenticationPrincipal CurrentUser user,
                                         @Valid @RequestBody ChatRequest request) {
        return answers.answer(user, request.query())
                .map(event -> ServerSentEvent.<String>builder()
                        .event(event.type())
                        .data(event.toWire())
                        .build());
    }

    @GetMapping("/citations/{childChunkKey}")
    TransDTO<java.util.Map<String, Object>> citation(@AuthenticationPrincipal CurrentUser user,
                                                     @PathVariable String childChunkKey) {
        return TransDTO.success(citations.resolve(user, childChunkKey));
    }
}
