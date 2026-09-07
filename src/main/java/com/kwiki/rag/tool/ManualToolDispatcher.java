package com.kwiki.rag.tool;

import com.kwiki.rag.orchestration.*;
import com.kwiki.rag.retrieval.*;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public final class ManualToolDispatcher {
    private final ToolRegistry registry;
    private final HybridRetrievalOrchestrator retrieval;

    public ManualToolDispatcher(ToolRegistry registry, HybridRetrievalOrchestrator retrieval) {
        this.registry = registry;
        this.retrieval = retrieval;
        registry.registerHandler(
                "es_search",
                (arguments, run, accumulated) -> {
                    var b = retrieval.budgets();
                    return retrieval.retrieve(
                            run.scope,
                            arguments,
                            accumulated,
                            b.distinctParentLimit,
                            b.parentContextCharBudget);
                });
    }

    public record Execution(ToolResult result, List<ParentEvidenceChunk> parents) {}

    public Execution execute(
            ToolRegistry.ValidatedCall call,
            RunContext run,
            HybridRetrievalOrchestrator.Accumulation accumulated,
            Map<String, Execution> completed,
            Map<String, String> identities) {
        run.authorize();
        String identity = call.call().name() + ":" + call.arguments().signature();
        String prior = identities.get(call.call().callId());
        if (prior != null) {
            if (!prior.equals(identity)) throw new RunFailure("conflicting-call-id");
            return completed.get(call.call().callId());
        }
        registry.get(call.call().name());
        run.toolCall();
        identities.put(call.call().callId(), identity);
        Execution execution;
        try {
            var found =
                    registry.handler(call.call().name())
                            .execute(call.arguments(), run, accumulated);
            run.authorize();
            execution =
                    new Execution(
                            new ToolResult(
                                    call.call().callId(),
                                    "SUCCESS",
                                    found.parents().stream()
                                            .map(ParentEvidenceChunk::parentChunkKey)
                                            .toList(),
                                    found.degradations(),
                                    null,
                                    Map.of("parentCount", found.parents().size())),
                            found.parents());
        } catch (Exception e) {
            run.authorize();
            String code = e instanceof RunFailure ? e.getMessage() : "retrieval-failed";
            execution =
                    new Execution(
                            new ToolResult(
                                    call.call().callId(),
                                    "ERROR",
                                    List.of(),
                                    List.of(),
                                    code,
                                    Map.of()),
                            List.of());
        }
        registry.validate("tool-result-v1", ToolRegistry.json(execution.result()));
        completed.put(call.call().callId(), execution);
        return execution;
    }
}
