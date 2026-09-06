package com.kwiki.rag.rewrite;

import java.util.Optional;

/** Port for rewrite-style LLM calls; adapters enforce deadlines and sanitization. */
public interface RewriteLlmPort {

    /** Completion content for the given instruction; empty on failure/timeout. */
    Optional<String> complete(String systemInstruction, String userPrompt);
}
