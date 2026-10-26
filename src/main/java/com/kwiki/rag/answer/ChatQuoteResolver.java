package com.kwiki.rag.answer;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.api.PageRevisionService;
import com.kwiki.wiki.domain.WikiPageRevision;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 按当前用户的读权限解析划线引用：无权访问或页面不存在时直接拒绝（在开流之前抛出），
 * 有权时补全页面标题，并尽力找到唯一包含引用的段落作为补充上下文。
 */
@Component
public class ChatQuoteResolver {
    private final PageRevisionService pages;

    public ChatQuoteResolver(PageRevisionService pages) {
        this.pages = pages;
    }

    public ChatQuote resolve(CurrentUser user, ChatQuote quote) {
        if (quote == null) return null;
        String text = quote.text() == null ? "" : quote.text().trim();
        if (text.isEmpty()) return null;
        if (quote.kbId() == null || quote.pageId() == null) {
            throw new IllegalArgumentException("quote requires kbId and pageId");
        }
        if (text.length() > ChatQuote.MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("quote exceeds the context budget");
        }
        long kbId = quote.kbId();
        long pageId = quote.pageId();
        String title = pages.title(user, kbId, pageId);
        String paragraph = paragraphContaining(pages.publishedContent(user, kbId, pageId), text);
        return new ChatQuote(kbId, pageId, title, text, paragraph);
    }

    /** 只在恰好一个段落包含引用且不过长时带上；草稿与已发布内容不一致、引用跨段等情况直接不带。 */
    private static String paragraphContaining(WikiPageRevision revision, String text) {
        String plain = revision == null ? null : revision.getPlainText();
        if (plain == null || plain.isBlank()) return null;
        List<String> matches = Arrays.stream(plain.split("\\R\\s*\\R")).filter(part -> part.contains(text)).toList();
        if (matches.size() != 1) return null;
        String paragraph = matches.get(0).trim();
        return paragraph.length() > ChatQuote.MAX_PARAGRAPH_CHARS ? null : paragraph;
    }
}
