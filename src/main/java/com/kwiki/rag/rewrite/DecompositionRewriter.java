package com.kwiki.rag.rewrite;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Schema-constrained decomposition into one to three distinct nonblank
 * subqueries. LLM-backed with a deterministic conjunction-split fallback;
 * duplicate, blank, over-limit, and unsafe outputs fall back to the original.
 */
@Component
public class DecompositionRewriter {

    static final int MAX_SUBQUERIES = 3;
    private static final Pattern SPLIT = Pattern.compile("\\s*(?:另外|此外|以及|还有|;|；|，然后)\\s*");

    private final RewriteLlmPort llm;

    public DecompositionRewriter(Optional<RewriteLlmPort> llm) {
        this.llm = llm.orElse(null);
    }

    public RewriteResult rewrite(String query) {
        List<String> subqueries = llm != null ? fromLlm(query) : List.<String>of();
        if (subqueries.isEmpty()) {
            subqueries = splitDeterministic(query);
        }
        if (subqueries.isEmpty()) {
            return RewriteResult.fallback(query, "decomposition-no-split");
        }
        return new RewriteResult(query, com.kwiki.rag.routing.RewriteMode.DECOMPOSITION,
                subqueries, llm == null || subqueries.equals(splitDeterministic(query))
                        ? "deterministic-split" : null);
    }

    private List<String> fromLlm(String query) {
        Optional<String> answer = llm.complete(
                "Split the compound question into 1 to 3 independent search questions. "
                        + "Output exactly one question per line, no numbering, nothing else.",
                query);
        if (answer.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String line : answer.get().strip().split("\\n+")) {
            String stripped = line.replaceFirst("^\\s*\\d+[.、)]\\s*", "").strip();
            if (!stripped.isBlank()) {
                cleaned.add(stripped);
            }
        }
        if (cleaned.size() < 1 || cleaned.size() > MAX_SUBQUERIES) {
            return List.of();
        }
        long distinct = cleaned.stream().distinct().count();
        if (distinct != cleaned.size()) {
            return List.of();
        }
        return cleaned;
    }

    private static List<String> splitDeterministic(String query) {
        String[] parts = SPLIT.split(query.strip());
        List<String> cleaned = new ArrayList<>();
        for (String part : parts) {
            String stripped = part.strip();
            if (!stripped.isBlank() && !cleaned.contains(stripped)) {
                cleaned.add(stripped);
            }
        }
        if (cleaned.size() <= 1) {
            return List.of();
        }
        return cleaned.size() > MAX_SUBQUERIES
                ? List.copyOf(cleaned.subList(0, MAX_SUBQUERIES))
                : cleaned;
    }
}
