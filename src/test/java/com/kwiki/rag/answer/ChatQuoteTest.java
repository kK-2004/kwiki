package com.kwiki.rag.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwiki.rag.orchestration.AgenticWorkflowPort;
import com.kwiki.rag.orchestration.RouteInput;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.api.PageRevisionService;
import com.kwiki.wiki.domain.WikiPageRevision;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

class ChatQuoteTest {

    final CurrentUser user = new CurrentUser(1L, "qa", false);

    @Test
    void 引用拼进工作流问题而用户消息仍存原话() {
        var workflow = mock(AgenticWorkflowPort.class);
        var sessions = mock(ChatSessionService.class);
        var run = new ChatSessionService.RunHandle("s", "c", "r", 1L, 2L, false);
        var quote = new ChatQuote(3L, 4L, "相机标定", "水平视场的连续性", "广角镜头要求水平视场的连续性，不能出现断裂。");
        when(sessions.begin(user, null, "c", null, "怎么理解", quote)).thenReturn(run);
        when(sessions.historyBefore(2L)).thenReturn(List.of());
        when(workflow.answerInSession(eq(user), anyString(), eq(List.of()), eq(Set.of()), eq(Set.of()), any(RouteInput.class)))
                .thenReturn(Flux.just(ChatStreamEvent.of("done", 1, "r", Map.of())));

        new AgenticAnswerService(workflow, provider(sessions), provider(mock(ChatRunEventStore.class)))
                .answer(user, "怎么理解", null, "c", null, Set.of(), Set.of(), null, quote).collectList().block();

        verify(workflow).answerInSession(eq(user), eq("""
                我在页面《相机标定》中划线引用了这段内容：
                「水平视场的连续性」
                它所在的段落：
                广角镜头要求水平视场的连续性，不能出现断裂。
                我的问题：怎么理解"""), eq(List.of()), eq(Set.of()), eq(Set.of()), any(RouteInput.class));
        verify(sessions).finish(user, run, "怎么理解", "", true, null);
        verify(sessions, never()).begin(any(), any(), any(), any(), anyString());
    }

    @Test
    void 解析按权限补全标题并只在唯一段落命中时带上段落() {
        var pages = mock(PageRevisionService.class);
        var revision = mock(WikiPageRevision.class);
        when(pages.title(user, 3L, 4L)).thenReturn("相机标定");
        when(pages.publishedContent(user, 3L, 4L)).thenReturn(revision);
        when(revision.getPlainText()).thenReturn("第一段。\n\n要求水平视场的连续性。\n\n另一段。");

        ChatQuote resolved = new ChatQuoteResolver(pages).resolve(user, ChatQuote.request(3L, 4L, "  水平视场的连续性 "));

        assertThat(resolved).isEqualTo(new ChatQuote(3L, 4L, "相机标定", "水平视场的连续性", "要求水平视场的连续性。"));

        when(revision.getPlainText()).thenReturn("水平视场的连续性\n\n再说水平视场的连续性");
        assertThat(new ChatQuoteResolver(pages).resolve(user, ChatQuote.request(3L, 4L, "水平视场的连续性")).paragraph()).isNull();
    }

    @Test
    void 无权访问页面时在开流之前拒绝() {
        var pages = mock(PageRevisionService.class);
        when(pages.title(user, 3L, 4L)).thenThrow(new AccessDeniedException("denied"));

        assertThatThrownBy(() -> new ChatQuoteResolver(pages).resolve(user, ChatQuote.request(3L, 4L, "x")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> new ChatQuoteResolver(pages).resolve(user, ChatQuote.request(3L, 4L, "x".repeat(4001))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ChatQuoteResolver(pages).resolve(user, ChatQuote.request(3L, 4L, "  "))).isNull();
    }

    @Test
    void 入库JSON可往返且不含段落() {
        var quote = new ChatQuote(3L, 4L, "相机标定", "水平视场", "段落");
        ChatQuote restored = ChatQuote.fromJson(quote.toJson());

        assertThat(restored).isEqualTo(new ChatQuote(3L, 4L, "相机标定", "水平视场", null));
        assertThat(restored.contextLine("怎么理解")).isEqualTo("「水平视场」怎么理解");
        assertThat(ChatQuote.fromJson("not json")).isNull();
        assertThat(ChatQuote.fromJson(null)).isNull();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            public T getObject() { return value; }
            public T getIfAvailable() { return value; }
        };
    }
}
