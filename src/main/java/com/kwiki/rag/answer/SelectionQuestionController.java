package com.kwiki.rag.answer;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.api.PageRevisionService;
import com.kwiki.wiki.api.SelectionQuestionLlmClient;
import com.kwiki.wiki.domain.WikiPageRevision;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.List;
import java.util.UUID;

/** 在从已授权修订版本重建上下文之后，流式输出选区问答的答案。 */
@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/pages/{pageId}")
@PreAuthorize("isAuthenticated()")
public class SelectionQuestionController {
    private static final int MAX_PARAGRAPH_CHARS = 12_000;
    private final PageRevisionService pages;
    private final SelectionQuestionLlmClient model;
    private final ResourceAuthorizationService authorization;

    public SelectionQuestionController(PageRevisionService pages, SelectionQuestionLlmClient model) {
        this(pages, model, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SelectionQuestionController(PageRevisionService pages, SelectionQuestionLlmClient model,
                                      ResourceAuthorizationService authorization) {
        this.pages = pages; this.model = model; this.authorization = authorization;
    }

    public record Request(@NotBlank String selectedText, @NotBlank String query) {}

    @PostMapping(value = "/selection-question", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> ask(@AuthenticationPrincipal CurrentUser user,
                                      @PathVariable long kbId, @PathVariable long pageId,
                                      @Valid @RequestBody Request request) {
        WikiPageRevision revision = pages.publishedContent(user, kbId, pageId);
        if (request.query().trim().length() > 2000 || request.selectedText().trim().length() > 4000) {
            throw new IllegalArgumentException("selection question exceeds the context budget");
        }
        String paragraph = paragraphContaining(revision.getPlainText(), request.selectedText());
        if (paragraph.length() > MAX_PARAGRAPH_CHARS) throw new IllegalArgumentException("selected paragraph exceeds the question context budget");
        SelectionQuestionLlmClient.SelectionContext context = new SelectionQuestionLlmClient.SelectionContext(
                pages.title(user, kbId, pageId), paragraph, request.selectedText().trim(), request.query().trim());
        String requestId = UUID.randomUUID().toString();
        return Flux.from(model.stream(context))
                .map(text -> {
                    if (authorization != null) authorization.require(user, pageId, ResourceAction.READ);
                    return ServerSentEvent.<String>builder().event("token").data(json(Map.of("requestId", requestId, "text", text))).build();
                })
                .concatWith(Flux.just(ServerSentEvent.<String>builder().event("done").data(json(Map.of("requestId", requestId))).build()))
                .onErrorResume(error -> Flux.just(ServerSentEvent.<String>builder().event("error").data(json(Map.of("requestId", requestId, "error", "selection_question_failed"))).build()));
    }

    private String paragraphContaining(String plainText, String selected) {
        String[] paragraphs = plainText.split("\\R\\s*\\R");
        String quote = selected.trim();
        List<String> matches = java.util.Arrays.stream(paragraphs).filter(part -> part.contains(quote)).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("selection is not in the current revision");
        if (matches.size() > 1) throw new IllegalArgumentException("selection is ambiguous; please select one paragraph");
        return matches.get(0);
    }

    private String json(Object value) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
        catch (Exception ex) { return "{}"; }
    }
}
