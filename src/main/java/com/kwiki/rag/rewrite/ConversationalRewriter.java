package com.kwiki.rag.rewrite;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Produces a self-contained query from explicit recent history. LLM-backed with a
 * deterministic fallback (context prefix) when the port is missing or fails.
 * History is bounded; unrelated turns are ignored by the prompt contract and the
 * fallback only uses the latest user turn.
 */
@Component
public class ConversationalRewriter {

    static final int MAX_HISTORY_TURNS = 6;

    private final RewriteLlmPort llm;

    public ConversationalRewriter(Optional<RewriteLlmPort> llm) {
        this.llm = llm.orElse(null);
    }

    public RewriteResult rewrite(String query, List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return RewriteResult.fallback(query, "no-history");
        }
        List<ChatTurn> bounded = history.size() <= MAX_HISTORY_TURNS
                ? history
                : history.subList(history.size() - MAX_HISTORY_TURNS, history.size());
        if (llm != null) {
            Optional<String> rewritten = llm.complete(
                    "Rewrite the query into one self-contained question using the chat history. "
                            + "Output only the rewritten question, nothing else.",
                    "History:\n" + render(bounded) + "\nQuery: " + query);
            if (rewritten.isPresent() && !rewritten.get().isBlank()) {
                return new RewriteResult(query,
                        com.kwiki.rag.routing.RewriteMode.CONVERSATIONAL,
                        List.of(rewritten.get().strip()), null);
            }
        }
        String lastUserTopic = latestUserTurn(bounded);
        return new RewriteResult(query,
                com.kwiki.rag.routing.RewriteMode.CONVERSATIONAL,
                List.of(query + "（上下文主题：" + lastUserTopic + "）"),
                "llm-unavailable");
    }

    private static String render(List<ChatTurn> turns) {
        StringBuilder builder = new StringBuilder();
        for (ChatTurn turn : turns) {
            builder.append(turn.role()).append(": ").append(turn.content()).append('\n');
        }
        return builder.toString();
    }

    private static String latestUserTurn(List<ChatTurn> turns) {
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).isUser()) {
                String content = turns.get(i).content().strip();
                return content.length() > 60 ? content.substring(0, 60) : content;
            }
        }
        return "";
    }
}
