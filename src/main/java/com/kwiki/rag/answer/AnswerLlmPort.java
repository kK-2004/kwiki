package com.kwiki.rag.answer;

import reactor.core.publisher.Flux;

/**
 * Streaming answer provider port: emits answer text incrementally; subscription
 * cancellation must propagate to the provider stream.
 */
public interface AnswerLlmPort {

    /** Streams answer deltas for the given prompt; errors are sanitized upstream. */
    Flux<String> streamAnswer(String prompt);
}
