package com.kwiki.graph.build.stage;

import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphExtractionInput;
import com.kwiki.graph.GraphExtractionResult;
import com.kwiki.graph.GraphExtractionValidationException;
import com.kwiki.graph.GraphExtractionValidator;
import com.kwiki.graph.GraphRelation;
import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.graph.GraphSourceChild;
import com.kwiki.graph.GraphSourceChunkListingPort;
import com.kwiki.graph.GraphSourceRef;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.build.RunArtifacts;
import com.kwiki.graph.build.RunContext;
import com.kwiki.graph.build.RunProgress;
import com.kwiki.graph.build.RetryPolicy;
import com.kwiki.graph.config.GraphBuildProperties;
import com.kwiki.graph.model.GraphModelClients;
import com.kwiki.graph.persistence.GraphBuildRunRecord;
import com.kwiki.graph.persistence.GraphExtractionCache;
import com.kwiki.graph.persistence.GraphExtractionJobPlanner;
import com.kwiki.graph.persistence.GraphExtractionJobSpec;
import com.kwiki.graph.plugin.GraphExtractorPlugin;
import com.kwiki.graph.plugin.GraphExtractorRegistry;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EXTRACTING 阶段：列举子块 → 缓存优先 → 抽取插件 → 结构校验 → 写缓存 →
 * 实体消解并把临时实体 id 改写为正式 id。块级并发与抽取端点并发一致。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class ExtractionStage implements GraphBuildStage {

    private static final int PROGRESS_EVERY = 50;

    private final GraphSourceChunkListingPort listing;
    private final GraphExtractorRegistry extractors;
    private final GraphExtractionCache cache;
    private final GraphExtractionJobPlanner planner;
    private final com.kwiki.graph.GraphEntityResolutionPort resolver; // 保守消解，保持同序
    private final SearchIndexVersionRepository versions;
    private final GraphBuildProperties buildProperties;
    private final GraphModelClients modelClients;

    public ExtractionStage(GraphSourceChunkListingPort listing,
                           GraphExtractorRegistry extractors,
                           GraphExtractionCache cache,
                           GraphExtractionJobPlanner planner,
                           com.kwiki.graph.GraphEntityResolutionPort resolver,
                           SearchIndexVersionRepository versions,
                           GraphBuildProperties buildProperties,
                           GraphModelClients modelClients) {
        this.listing = listing;
        this.extractors = extractors;
        this.cache = cache;
        this.planner = planner;
        this.resolver = resolver;
        this.versions = versions;
        this.buildProperties = buildProperties;
        this.modelClients = modelClients;
    }

    @Override
    public void execute(RunContext ctx, RunArtifacts artifacts) {
        GraphBuildRunRecord run = ctx.run();
        GraphExtractorPlugin extractor = extractors.require(run.extractor());
        var config = versions.findByVersionNumber(run.chunkIndexVersion())
                .orElseThrow(() -> GraphBuildFailure.permanent("CHUNK_VERSION_GONE",
                        "Chunk 版本 v" + run.chunkIndexVersion() + " 不存在或已删除"))
                .editableConfig();
        RetryPolicy retry = RetryPolicy.of(buildProperties.maxAttempts(),
                buildProperties.retryBaseDelay());

        List<GraphSourceChild> children = listing.listChildren(run.chunkPhysicalIndex(),
                run.kbId(), buildProperties.maxChunksPerRun());
        List<GraphSourceChunk> sources = new ArrayList<>(children.size());
        Map<String, com.kwiki.rag.retrieval.ChunkHit> childrenBySourceChunkId = new LinkedHashMap<>();
        for (GraphSourceChild child : children) {
            GraphSourceChunk source = child.toSourceChunk(run.chunkIndexVersion());
            sources.add(source);
            childrenBySourceChunkId.put(source.sourceChunkId(), child.hit());
        }
        artifacts.sources = sources;
        artifacts.childrenBySourceChunkId = childrenBySourceChunkId;

        ctx.progress(new RunProgress(sources.size(), 0, 0, 0, 0));
        if (sources.isEmpty()) {
            return;
        }

        Map<String, List<GraphEntity>> entitiesByChunk = new LinkedHashMap<>();
        Map<String, List<GraphRelation>> relationsByChunk = new LinkedHashMap<>();
        AtomicLong cacheHits = new AtomicLong();
        AtomicLong extracted = new AtomicLong();
        // 块级并发与抽取端点 maxConcurrency 一致；模型调用另经全局限流器
        Semaphore permits = new Semaphore(modelClients.extraction()
                .map(endpoint -> endpoint.limiter().permits()).orElse(4));
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Runnable> tasks = sources.stream().map(source -> (Runnable) () -> {
                permits.acquireUninterruptibly();
                try {
                    processChunk(ctx, extractor, retry, source,
                            childrenBySourceChunkId.get(source.sourceChunkId()),
                            entitiesByChunk, relationsByChunk, cacheHits, extracted,
                            sources.size());
                } finally {
                    permits.release();
                }
            }).toList();
            List<java.util.concurrent.CompletableFuture<Void>> futures =
                    tasks.stream()
                            .map(task -> java.util.concurrent.CompletableFuture.runAsync(task, pool))
                            .toList();
            for (var future : futures) {
                future.join();
            }
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (java.util.concurrent.CompletionException wrapped) {
            throw unwrapped(wrapped);
        } finally {
            pool.shutdownNow();
        }

        mergeChunkResults(artifacts, entitiesByChunk, relationsByChunk);
        ctx.progress(new RunProgress(sources.size(), extracted.get(), cacheHits.get(), 0, 0));
    }

    private void processChunk(RunContext ctx,
                              GraphExtractorPlugin extractor,
                              RetryPolicy retry,
                              GraphSourceChunk source,
                              com.kwiki.rag.retrieval.ChunkHit hit,
                              Map<String, List<GraphEntity>> entitiesByChunk,
                              Map<String, List<GraphRelation>> relationsByChunk,
                              AtomicLong cacheHits,
                              AtomicLong extracted,
                              long sourcesTotal) {
        ctx.throwIfCancelled();
        String sourceChunkId = source.sourceChunkId();
        Optional<GraphExtractionResult> cached = cache.findReady(source,
                extractor.id(), extractor.promptVersion(), ctx.run().entityLinkingVersion());
        GraphExtractionResult result;
        if (cached.isPresent()) {
            cacheHits.incrementAndGet();
            result = cached.get();
        } else {
            long extractionId = planner.plan(new GraphExtractionJobSpec(source, extractor.id(),
                            extractor.promptVersion(), ctx.run().entityLinkingVersion(),
                            ctx.run().contentEpoch(), ctx.run().securityEpoch()), true, true)
                    .orElseThrow(() -> new IllegalStateException("抽取缓存行无法回读身份"));
            GraphExtractionInput input = new GraphExtractionInput(source, hit.content(), "",
                    extractor.id(), extractor.promptVersion(), ctx.run().entityLinkingVersion());
            try {
                result = retry.run(() -> extractor.extract(input));
            } catch (GraphBuildFailure failure) {
                cache.markFailed(extractionId, failure.code(), String.valueOf(failure.getMessage()));
                throw failure;
            }
            try {
                GraphExtractionValidator.validate(input, result);
            } catch (GraphExtractionValidationException invalid) {
                cache.markFailed(extractionId, "EXTRACTION_INVALID",
                        String.valueOf(invalid.getMessage()));
                throw GraphBuildFailure.permanent("EXTRACTION_INVALID",
                        "抽取结果未通过结构校验：" + invalid.getMessage());
            }
            cache.markReady(extractionId, result);
        }

        // 实体消解：同序返回正式实体，建立「临时 id → 正式 id」映射
        List<GraphEntity> resolved = resolver.resolve(ctx.run().kbId(), result.entities(),
                hit.content(), extractor.id());
        Map<String, String> formalIds = new HashMap<>();
        for (int i = 0; i < result.entities().size(); i++) {
            String tempId = result.entities().get(i).entityId();
            String formalId = i < resolved.size() ? resolved.get(i).entityId() : null;
            if (tempId != null && formalId != null) {
                formalIds.put(tempId, formalId);
            }
        }
        List<GraphRelation> rewritten = new ArrayList<>();
        for (GraphRelation relation : result.relations()) {
            String formalSource = formalIds.get(relation.sourceEntityId());
            String formalTarget = formalIds.get(relation.targetEntityId());
            if (formalSource == null || formalTarget == null) {
                continue;
            }
            rewritten.add(rewrite(relation, formalSource, formalTarget, sourceChunkId));
        }
        entitiesByChunk.put(sourceChunkId, resolved);
        relationsByChunk.put(sourceChunkId, rewritten);
        extracted.incrementAndGet();
        long done = extracted.get();
        if (done % PROGRESS_EVERY == 0) {
            ctx.progress(new RunProgress(sourcesTotal, done, cacheHits.get(), 0, 0));
        }
    }

    /** 用正式端点 id 重算关系身份；引文区间保持不变。 */
    private static GraphRelation rewrite(GraphRelation relation, String formalSource,
                                         String formalTarget, String sourceChunkId) {
        String relationId = sha256(sourceChunkId + "|" + formalSource + "|"
                + relation.predicate().name() + "|" + formalTarget);
        return new GraphRelation(relationId, formalSource, relation.predicate(), formalTarget,
                relation.confidence(), relation.sourceRefs());
    }

    /** 按正式 entityId 去重汇入产物；关系按重算后的 relationId 去重。 */
    private static void mergeChunkResults(RunArtifacts artifacts,
                                          Map<String, List<GraphEntity>> entitiesByChunk,
                                          Map<String, List<GraphRelation>> relationsByChunk) {
        Map<String, GraphEntity> entityById = new LinkedHashMap<>();
        Map<String, List<String>> extractedEntityIds = new LinkedHashMap<>();
        for (Map.Entry<String, List<GraphEntity>> entry : entitiesByChunk.entrySet()) {
            List<String> ids = new ArrayList<>();
            for (GraphEntity entity : entry.getValue()) {
                if (entity.entityId() != null && entityById.putIfAbsent(
                        entity.entityId(), entity) == null) {
                    ids.add(entity.entityId());
                }
            }
            extractedEntityIds.put(entry.getKey(), ids);
        }
        Map<String, GraphRelation> relationById = new LinkedHashMap<>();
        for (List<GraphRelation> chunkRelations : relationsByChunk.values()) {
            for (GraphRelation relation : chunkRelations) {
                relationById.putIfAbsent(relation.relationId(), relation);
            }
        }
        artifacts.entities = List.copyOf(entityById.values());
        artifacts.relations = List.copyOf(relationById.values());
        artifacts.extractedEntityIds = extractedEntityIds;
    }

    private static RuntimeException unwrapped(
            java.util.concurrent.CompletionException wrapped) {
        Throwable cause = wrapped.getCause() == null ? wrapped : wrapped.getCause();
        return cause instanceof RuntimeException runtime ? runtime
                : new IllegalStateException(cause);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("运行时缺少 SHA-256", failure);
        }
    }
}
