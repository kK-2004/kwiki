package com.kwiki.testutil;

import com.kwiki.rag.answer.*;
import com.kwiki.rag.quality.*;
import com.kwiki.rag.retrieval.*;
import com.kwiki.rag.rewrite.*;
import com.kwiki.rag.routing.*;
import com.kwiki.rag.tool.*;
import com.kwiki.wiki.access.*;

import java.util.*;

public final class AgenticTestSupport {
    public static AgenticAnswerService service(
            RuleFirstRouter router,
            QueryRewriteOrchestrator rewrites,
            HybridRetrievalOrchestrator retrieval,
            EvidenceAssembler assembler,
            AnswerLlmPort llm,
            AuthorizationScopeResolver scopes,
            ChatPersistenceService persistence,
            io.micrometer.core.instrument.MeterRegistry metrics,
            ScopeVersionService versions) {
        var registry = new ToolRegistry();
        RetrievalPlannerPort planner =
                (q, queries, gaps, history, error) ->
                        List.of(
                                new ToolCall(
                                        "call-" + history.size(),
                                        "es_search",
                                        ToolRegistry.json(
                                                new SearchArguments(
                                                        queries, RetrievalStrategy.HYBRID, 50)),
                                        "turn-" + history.size()));
        QualityAnalyzerPort qa =
                (q, queries, evidence) ->
                        new QualityDecision(
                                QualityDecision.Action.GENERATE,
                                true,
                                evidence.stream().map(ParentEvidence::parentChunkKey).toList(),
                                List.of(),
                                "sufficient",
                                List.of(),
                                QualityDecision.ReturnKind.NONE);
        try {
            return new AgenticAnswerService(
                    new com.kwiki.infrastructure.orchestration.LangGraphAgenticWorkflow(
                            router,
                            rewrites,
                            retrieval,
                            assembler,
                            llm,
                            qa,
                            planner,
                            registry,
                            new ManualToolDispatcher(registry, retrieval),
                            scopes,
                            versions,
                            persistence,
                            metrics,
                            com.kwiki.rag.orchestration.AgenticLimits.defaults(),
                            Optional.empty(),
                            Optional.empty()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
