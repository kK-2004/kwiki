package com.kwiki.infrastructure.ai;

import com.kwiki.rag.answer.AnswerLlmPort;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.api.PageRevisionService;
import com.kwiki.wiki.api.PublicationNotePort;
import com.kwiki.wiki.domain.WikiPageRevision;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** 根据首份文档或「已发布到草稿」的差异生成一段简短的发布说明。 */
@Service
public class PublicationNoteAdapter implements PublicationNotePort {
    private final AnswerLlmPort llm;
    private final PageRevisionService revisions;

    public PublicationNoteAdapter(AnswerLlmPort llm, PageRevisionService revisions) {
        this.llm = llm;
        this.revisions = revisions;
    }

    @Override
    public String generate(CurrentUser user, long kbId, long pageId, String markdown) {
        String previous;
        try {
            WikiPageRevision published = revisions.publishedContent(user, kbId, pageId);
            previous = published.getMarkdown();
        } catch (RuntimeException noPublishedVersion) {
            previous = "";
        }
        String prompt = previous.isBlank()
                ? "请根据以下首次发布的 Wiki 内容生成一句中文发布说明，最多60字，只输出说明：\n" + bounded(markdown)
                : "请比较上一已发布版本与本次内容，生成一句中文发布说明，最多60字，只输出说明。\n上一版：\n"
                    + bounded(previous) + "\n本次：\n" + bounded(markdown);
        try {
            String note = llm.streamAnswer(prompt).collectList().map(parts -> String.join("", parts))
                    .block(Duration.ofSeconds(30));
            if (note != null && !note.isBlank()) return note.strip().substring(0, Math.min(300, note.strip().length()));
        } catch (RuntimeException ignored) {
            // 当可选的建议生成失败时，发布功能仍然可用。
        }
        return previous.isBlank() ? "首次发布页面内容" : "更新页面内容";
    }

    private static String bounded(String markdown) {
        String value = markdown == null ? "" : markdown;
        return value.substring(0, Math.min(value.length(), 12000));
    }
}
