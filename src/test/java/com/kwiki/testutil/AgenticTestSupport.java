package com.kwiki.testutil;

import com.kwiki.rag.activity.AgenticDebugLogger;
import com.kwiki.rag.answer.*;
import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.quality.QualityAssessment;
import com.kwiki.rag.quality.QualityV2AnalyzerPort;
import com.kwiki.rag.quality.QualityV2Input;
import com.kwiki.rag.retrieval.*;
import com.kwiki.rag.rewrite.ChatTurn;
import com.kwiki.rag.rewrite.FeedbackRewriteInput;
import com.kwiki.rag.rewrite.FeedbackRewritePort;
import com.kwiki.rag.routing.RuleFirstRouter;
import com.kwiki.rag.answer.AnswerLlmPort;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ScopeVersionService;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * 用脚本化叶子适配器组装 QA 门禁工作流：召回
 * 分支、父分块获取器、回答模型、quality-v2 评审与反馈
 * 改写器都可脚本化，而状态机、RRF 融合、预算与
 * 活动/调试管道都真实运行 —— 测试观察到的是真实的排名与
 * 阶段顺序，而不是它们的模拟对象。
 */
public final class AgenticTestSupport {

    /** 脚本化召回分支：按分支返回配置顺序的命中结果。 */
    public static final class ScriptRecall implements ChildRecallPort {
        public final ChildRecallPort.Branch branch;
        public List<ChunkHit> hits = List.of();
        public final List<Integer> topKs = new CopyOnWriteArrayList<>();
        public final List<String> queries = new CopyOnWriteArrayList<>();

        public ScriptRecall(ChildRecallPort.Branch branch) {
            this.branch = branch;
        }

        @Override
        public List<ChunkHit> search(String effectiveQuery, float[] queryVector,
                                     ScopeFilter scopeFilter, int topK) {
            queries.add(effectiveQuery);
            topKs.add(topK);
            return hits.stream().limit(topK).toList();
        }
    }

    /** 脚本化父分块获取器，为请求的 key 返回配置好的父分块。 */
    public static final class ScriptParentFetcher
            implements ParentEvidenceResolver.ParentChunkFetcher {
        public final Map<String, ParentEvidenceChunk> byKey = new LinkedHashMap<>();
        public final List<List<String>> requestedKeys = new CopyOnWriteArrayList<>();

        @Override
        public List<ParentEvidenceChunk> fetchByKeys(List<String> parentChunkKeys,
                                                     ScopeFilter scopeFilter) {
            requestedKeys.add(List.copyOf(parentChunkKeys));
            List<ParentEvidenceChunk> result = new ArrayList<>();
            for (String key : parentChunkKeys) {
                ParentEvidenceChunk chunk = byKey.get(key);
                if (chunk != null) result.add(chunk);
            }
            return result;
        }
    }

    /** 脚本化回答模型，按队列提供文本；记录每一次 prompt。 */
    public static final class ScriptAnswer implements AnswerLlmPort {
        public final List<String> prompts = new CopyOnWriteArrayList<>();
        public final ArrayDeque<String> scripted = new ArrayDeque<>();

        @Override
        public reactor.core.publisher.Flux<String> streamAnswer(String prompt) {
            prompts.add(prompt);
            String text = scripted.isEmpty() ? "默认候选回答" : scripted.pop();
            return reactor.core.publisher.Flux.just(text);
        }
    }

    /** 带逐输入通过谓词的脚本化 quality-v2 评审。 */
    public static final class ScriptQuality implements QualityV2AnalyzerPort {
        public final List<QualityV2Input> inputs = new CopyOnWriteArrayList<>();
        public Predicate<QualityV2Input> passes = input -> true;
        public boolean unavailable;

        @Override
        public QualityAssessment assess(QualityV2Input input) {
            inputs.add(input);
            if (unavailable) {
                throw new com.kwiki.rag.orchestration.RunFailure("qa-unavailable");
            }
            boolean pass = passes.test(input);
            return new QualityAssessment(0.95, 0.95, 0.95, pass,
                    pass ? List.of(firstEvidenceId(input)) : List.of(),
                    pass ? List.of() : List.of("证据不足"),
                    pass ? List.of() : List.of("关键方面"),
                    pass ? "sufficient" : "coverage-missing",
                    pass ? "全部达标" : "覆盖不足");
        }

        private static String firstEvidenceId(QualityV2Input input) {
            if (input.candidate().evidenceLevel() == CandidateAnswer.EvidenceLevel.PARENT) {
                return input.candidate().parentEvidence().isEmpty()
                        ? "" : input.candidate().parentEvidence().get(0).parentChunkKey();
            }
            return input.candidate().retainedChildren().isEmpty()
                    ? "" : input.candidate().retainedChildren().get(0).chunkKey();
        }
    }

    /** 脚本化反馈改写器，记录每一次结构化输入。 */
    public static final class ScriptRewriter implements FeedbackRewritePort {
        public final List<FeedbackRewriteInput> inputs = new CopyOnWriteArrayList<>();
        public final ArrayDeque<Optional<String>> scripted = new ArrayDeque<>();

        @Override
        public Optional<String> rewrite(FeedbackRewriteInput input) {
            inputs.add(input);
            return scripted.isEmpty()
                    ? Optional.of("改写后的检索问题 " + inputs.size())
                    : scripted.pop();
        }
    }

    /** 真实状态机 + 预算 + RRF，搭配脚本化的叶子适配器。 */
    public static final class Harness {
        public final ScriptRecall bm25;
        public final ScriptRecall vector;
        public final ScriptParentFetcher parents;
        public final ScriptAnswer answer;
        public final ScriptQuality quality;
        public final ScriptRewriter rewriter;
        public final QaChildRetrievalService childRetrieval;
        public final QaParentFetchService parentFetch;
        public final QaRetrievalBudgets budgets;
        public final com.kwiki.rag.orchestration.AgenticWorkflowPort workflow;

        Harness(ScriptRecall bm25, ScriptRecall vector, ScriptParentFetcher parents,
                ScriptAnswer answer, ScriptQuality quality, ScriptRewriter rewriter,
                QaChildRetrievalService childRetrieval, QaParentFetchService parentFetch,
                QaRetrievalBudgets budgets,
                com.kwiki.rag.orchestration.AgenticWorkflowPort workflow) {
            this.bm25 = bm25;
            this.vector = vector;
            this.parents = parents;
            this.answer = answer;
            this.quality = quality;
            this.rewriter = rewriter;
            this.childRetrieval = childRetrieval;
            this.parentFetch = parentFetch;
            this.budgets = budgets;
            this.workflow = workflow;
        }
    }

    public static Harness harness(RuleFirstRouter router,
                                  AuthorizationScopeResolver scopes,
                                  ScopeVersionService versions,
                                  ChatPersistenceService persistence,
                                  io.micrometer.core.instrument.MeterRegistry metrics) {
        var bm25 = new ScriptRecall(ChildRecallPort.Branch.BM25);
        var vector = new ScriptRecall(ChildRecallPort.Branch.VECTOR);
        var parents = new ScriptParentFetcher();
        var answer = new ScriptAnswer();
        var quality = new ScriptQuality();
        var rewriter = new ScriptRewriter();
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
        var budgets = new QaRetrievalBudgets(20, 8, 50, 20, 8, 16, 12000, 24000, 24000);
        var childRetrieval = new QaChildRetrievalService(embeddings, recall, versions, budgets);
        var resolver = new ParentEvidenceResolver(Optional.of(parents));
        var assembler = new EvidenceAssembler(new RetrievalBudgets(50, 40, 8, 3, 24000,
                java.time.Duration.ofSeconds(5)));
        var parentFetch = new QaParentFetchService(resolver, assembler, versions);
        var candidates = new CandidateGenerator(answer, 32000);
        var logger = new AgenticDebugLogger();
        try {
            var workflow = new com.kwiki.infrastructure.orchestration.LangGraphAgenticWorkflow(
                    router, candidates, quality, rewriter, childRetrieval, parentFetch,
                    budgets, scopes, versions, persistence, metrics, AgenticLimits.defaults(),
                    logger, 0.80, Optional.empty(), Optional.empty());
            return new Harness(bm25, vector, parents, answer, quality, rewriter,
                    childRetrieval, parentFetch, budgets, workflow);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AgenticTestSupport() {
    }

    /** 供引用对话轮次的测试使用的辅助方法。 */
    public static List<ChatTurn> history(String... turns) {
        List<ChatTurn> result = new ArrayList<>();
        for (int i = 0; i + 1 < turns.length; i += 2) {
            result.add(new ChatTurn(turns[i], turns[i + 1]));
        }
        return result;
    }
}
