package com.kwiki.wiki.api;

import org.reactivestreams.Publisher;

/** 选区问答专用的模型边界，与智能体化对话状态隔离。 */
public interface SelectionQuestionLlmClient {
    Publisher<String> stream(SelectionContext context);

    record SelectionContext(String title, String fullParagraph, String selectedText, String query) {}
}
