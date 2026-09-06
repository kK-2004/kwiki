package com.kwiki.rag.routing;

import java.util.Optional;

/** Port for the schema-constrained router LLM; adapters must respect deadlines. */
public interface RouterLlmPort {

    /** Raw (already JSON-parsed but unvalidated) LLM decision; empty on any failure. */
    Optional<java.util.Map<String, Object>> askRouter(String normalizedQuery, String matchTrace);
}
