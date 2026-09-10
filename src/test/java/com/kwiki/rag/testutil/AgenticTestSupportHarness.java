package com.kwiki.rag.testutil;

import com.kwiki.infrastructure.orchestration.LangGraphAgenticWorkflow;
import com.kwiki.rag.activity.AgenticDebugLogger;
import com.kwiki.rag.answer.AnswerLlmPort;
import com.kwiki.rag.answer.CandidateGenerator;
import com.kwiki.rag.answer.ChatPersistenceService;
import com.kwiki.rag.answer.EvidenceAssembler;
import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.quality.QualityV2AnalyzerPort;
import com.kwiki.rag.rewrite.FeedbackRewritePort;
import com.kwiki.rag.retrieval.ChildRecallPort;
import com.kwiki.rag.retrieval.ConcurrentRecallService;
import com.kwiki.rag.retrieval.ParentEvidenceResolver;
import com.kwiki.rag.retrieval.QaChildRetrievalService;
import com.kwiki.rag.retrieval.QaParentFetchService;
import com.kwiki.rag.retrieval.QaRetrievalBudgets;
import com.kwiki.rag.retrieval.RetrievalBudgets;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ScopeVersionService;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Builds a QA-gated {@link com.kwiki.rag.orchestration.AgenticWorkflowPort}
 * with functional recall/parent/LLM scripts and an always-passing quality
 * review — the state machine, budgets and RRF run for real.
 */
public final class AgenticTestSupportHarness {

    public interface RecallScript extends ChildRecallPort {
    }

    public interface ParentScript extends ParentEvidenceResolver.ParentChunkFetcher {
    }

    private AgenticTestSupportHarness() {
    }

    public static com.kwiki.rag.orchestration.AgenticWorkflowPort build(
            RuleFirstRouter router,
            AuthorizationScopeResolver scopes,
            ScopeVersionService versions,
            ChatPersistenceService persistence,
            io.micrometer.core.instrument.MeterRegistry metrics,
            RecallScript bm25,
            RecallScript vector,
            ParentScript parents,
            Supplier<reactor.core.publisher.Flux<String>> llm) {
        try {
            return buildWorkflow(router, scopes, versions, persistence, metrics,
                    bm25, vector, parents, llm);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static LangGraphAgenticWorkflow buildWorkflow(
            RuleFirstRouter router,
            AuthorizationScopeResolver scopes,
            ScopeVersionService versions,
            ChatPersistenceService persistence,
            io.micrometer.core.instrument.MeterRegistry metrics,
            RecallScript bm25,
            RecallScript vector,
            ParentScript parents,
            Supplier<reactor.core.publisher.Flux<String>> llm) throws Exception {
        var recall = new ConcurrentRecallService(bm25, vector);
        var embeddings = new com.kwiki.indexing.pipeline.ChunkEmbeddingPort() {
            @Override
            public List<float[]> embed(List<String> texts) {
                return texts.stream().map(text -> new float[4]).toList();
            }

            @Override
            public String cacheIdentity() {
                return "test";
            }
        };
        var qaBudgets = new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 12000, 24000, 24000);
        var childRetrieval = new QaChildRetrievalService(embeddings, recall, versions, qaBudgets);
        var resolver = new ParentEvidenceResolver(Optional.of(parents));
        var assembler = new EvidenceAssembler(new RetrievalBudgets(
                50, 40, 8, 3, 24000, Duration.ofSeconds(5)));
        var parentFetch = new QaParentFetchService(resolver, assembler, versions);
        AnswerLlmPort answerPort = prompt -> llm.get();
        var candidates = new CandidateGenerator(answerPort, 32000);
        QualityV2AnalyzerPort quality = input -> new com.kwiki.rag.quality.QualityAssessment(
                0.95, 0.95, 0.95, true,
                List.of(firstEvidenceId(input)), List.of(), List.of(), "sufficient", "全部达标");
        FeedbackRewritePort rewriter = input -> Optional.of("改写后的检索问题");
        return new LangGraphAgenticWorkflow(
                router, candidates, quality, rewriter, childRetrieval, parentFetch,
                qaBudgets, scopes, versions, persistence, metrics, AgenticLimits.defaults(),
                new AgenticDebugLogger(), 0.80, Optional.empty(), Optional.empty());
    }

    private static String firstEvidenceId(com.kwiki.rag.quality.QualityV2Input input) {
        if (input.candidate().evidenceLevel()
                == com.kwiki.rag.answer.CandidateAnswer.EvidenceLevel.PARENT) {
            return input.candidate().parentEvidence().isEmpty()
                    ? "" : input.candidate().parentEvidence().get(0).parentChunkKey();
        }
        return input.candidate().retainedChildren().isEmpty()
                ? "" : input.candidate().retainedChildren().get(0).chunkKey();
    }
}
