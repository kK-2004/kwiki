package com.kwiki.rag.rewrite;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bounded expansion for short/low-information queries. The LLM contract forbids
 * changing named entities or adding permission/infrastructure terms; a guard
 * rejects outputs that violate it and falls back to the original query.
 */
@Component
public class ExpansionRewriter {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "(kbId|scope|authorization|sk-[A-Za-z0-9_-]{8,}|password|elasticsearch|\"bool\")",
            Pattern.CASE_INSENSITIVE);

    static final int MAX_EXPANSION_CHARS = 160;

    private final RewriteLlmPort llm;

    public ExpansionRewriter(Optional<RewriteLlmPort> llm) {
        this.llm = llm.orElse(null);
    }

    public RewriteResult rewrite(String query) {
        if (llm == null) {
            return RewriteResult.fallback(query, "llm-unavailable");
        }
        Optional<String> expanded = llm.complete(
                "Expand the short query with at most three useful domain search terms for an "
                        + "enterprise wiki. Keep every named entity EXACTLY as written. "
                        + "Output only the expanded query, nothing else.",
                query);
        if (expanded.isEmpty()) {
            return RewriteResult.fallback(query, "llm-unavailable");
        }
        String candidate = expanded.get().strip();
        if (candidate.isBlank() || candidate.length() > MAX_EXPANSION_CHARS
                || FORBIDDEN.matcher(candidate).find()
                || !candidate.contains(primaryEntityOf(query))) {
            return RewriteResult.fallback(query, "expansion-guard-rejected");
        }
        return new RewriteResult(query, com.kwiki.rag.routing.RewriteMode.EXPANSION,
                List.of(candidate), null);
    }

    /** Longest token is treated as the named entity the expansion must preserve. */
    static String primaryEntityOf(String query) {
        String[] tokens = query.strip().split("\\s+");
        String longest = "";
        for (String token : tokens) {
            if (token.length() > longest.length()) {
                longest = token;
            }
        }
        return longest;
    }
}
