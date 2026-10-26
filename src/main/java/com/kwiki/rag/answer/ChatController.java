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

/** SSE 对话流与已授权的引用解析。 */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("isAuthenticated()")
public class ChatController {

    private final AgenticAnswerService answers;
    private final CitationService citations;
    private final ChatQuoteResolver quotes;

    public ChatController(AgenticAnswerService answers, CitationService citations) {
        this(answers, citations, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChatController(AgenticAnswerService answers, CitationService citations, ChatQuoteResolver quotes) {
        this.answers = answers;
        this.citations = citations;
        this.quotes = quotes;
    }

    /** 划线引用：只接收定位与文本，标题与段落由服务端按权限解析。 */
    public record QuoteRequest(Long kbId, Long pageId, String text) {}

    public record ChatRequest(@NotBlank String query,
                              String sessionId,
                              String clientMessageId,
                              String agentId,
                              java.util.Set<Long> knowledgeBaseIds,
                              java.util.Set<Long> pageIds,
                              ClarificationReply clarification,
                              QuoteRequest quote) {
        public ChatRequest(String query) {
            this(query, null, null, null, java.util.Set.of(), java.util.Set.of(), null, null);
        }
        public ChatRequest(String query, String sessionId, String clientMessageId, String agentId) {
            this(query, sessionId, clientMessageId, agentId, java.util.Set.of(), java.util.Set.of(), null, null);
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> stream(
            @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody ChatRequest request) {
        // 引用在开流之前解析：无权访问或页面不存在时直接以 HTTP 错误拒绝，而不是在流里失败。
        ChatQuote quote = request.quote() == null || quotes == null ? null
                : quotes.resolve(user, ChatQuote.request(request.quote().kbId(), request.quote().pageId(), request.quote().text()));
        Flux<ChatStreamEvent> events = request.sessionId() == null && request.clientMessageId() == null && quote == null
                ? answers.answer(user, request.query())
                : answers.answer(user, request.query(), request.sessionId(), request.clientMessageId(), request.agentId(),
                        request.knowledgeBaseIds(), request.pageIds(), request.clarification(), quote);
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
