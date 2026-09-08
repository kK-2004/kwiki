package com.kwiki.wiki.api;

import org.reactivestreams.Publisher;

/** Dedicated model boundary for a selection question, isolated from agentic chat state. */
public interface SelectionQuestionLlmClient {
    Publisher<String> stream(SelectionContext context);

    record SelectionContext(String title, String fullParagraph, String selectedText, String query) {}
}
