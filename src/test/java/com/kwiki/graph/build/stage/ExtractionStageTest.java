package com.kwiki.graph.build.stage;

import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphEntityType;
import com.kwiki.graph.GraphExtractionPrompt;
import com.kwiki.graph.GraphExtractionResult;
import com.kwiki.graph.GraphPredicate;
import com.kwiki.graph.GraphRelation;
import com.kwiki.graph.GraphSourceChild;
import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.graph.GraphSourceChunkListingPort;
import com.kwiki.graph.GraphSourceRef;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.build.RunArtifacts;
import com.kwiki.graph.build.RunContext;
import com.kwiki.graph.build.RunProgress;
import com.kwiki.graph.build.RetryPolicy;
import com.kwiki.graph.config.GraphBuildProperties;
import com.kwiki.graph.model.GraphModelClients;
import com.kwiki.graph.persistence.GraphExtractionCache;
import com.kwiki.graph.persistence.GraphExtractionJobPlanner;
import com.kwiki.graph.persistence.GraphExtractionJobSpec;
import com.kwiki.graph.persistence.GraphBuildRunRecord;
import com.kwiki.graph.persistence.GraphBuildStage;
import com.kwiki.graph.persistence.GraphBuildState;
import com.kwiki.graph.plugin.GraphExtractorPlugin;
import com.kwiki.graph.plugin.GraphExtractorRegistry;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.EntityLinkingStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 抽取阶段：缓存命中、插件调用、临时 id 改写、校验失败与取消。 */
class ExtractionStageTest {

    private static final String CONTENT = "Kwiki uses ArcadeDB for graph storage.";

    private static ChunkHit hit() {
        return new ChunkHit("kb7:page:1:chunk:0", "kb7:page:1", 7L, "PAGE", 1L, 3L,
                "h", 0, CONTENT.length(), CONTENT, List.of(), null, List.of(),
                "entity-linking-v1", EntityLinkingStatus.PENDING);
    }

    private static GraphSourceChild child() {
        return new GraphSourceChild(hit(), 2L, "parser-1", "chunker-1");
    }

    private static GraphBuildRunRecord run() {
        return new GraphBuildRunRecord(12L, 9L, 7L, 5, "kwiki-chunks-v5", 6L,
                "kwiki-communities-v6-kb7", 42L, "kwiki-schema-1", "entity-linking-v1",
                8L, 2L, GraphBuildState.EXTRACTING, GraphBuildStage.EXTRACTING, 3L,
                "owner-a", null, 0L, "kwiki-extractor-1", "arcadedb-leiden-1",
                "kwiki-summarizer-1");
    }

    private static GraphBuildProperties buildProperties() {
        return new GraphBuildProperties(Duration.ofSeconds(10), 2, Duration.ofMinutes(2),
                1, Duration.ZERO, 100, 50);
    }

    private static GraphExtractorPlugin pluginThrowing() {
        return new GraphExtractorPlugin() {
            @Override
            public String id() {
                return "kwiki-extractor-1";
            }

            @Override
            public String label() {
                return "x";
            }

            @Override
            public String promptVersion() {
                return GraphExtractionPrompt.VERSION;
            }

            @Override
            public GraphExtractionResult extract(com.kwiki.graph.GraphExtractionInput input) {
                throw new AssertionError("缓存命中时不应调用插件");
            }
        };
    }

    private static ExtractionStage stage(GraphSourceChunkListingPort listing,
                                         GraphExtractorRegistry registry,
                                         GraphExtractionCache cache,
                                         GraphExtractionJobPlanner planner,
                                         com.kwiki.graph.GraphEntityResolutionPort resolver) {
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        SearchIndexVersion version = mock(SearchIndexVersion.class);
        when(version.editableConfig()).thenReturn(new EditableIndexConfig("parser-1",
                "chunker-1", "provider", "embed-model", 1024, "kwiki-schema-1"));
        when(versions.findByVersionNumber(5)).thenReturn(Optional.of(version));
        return new ExtractionStage(listing, registry, cache, planner, resolver, versions,
                buildProperties(), modelClients());
    }

    private static GraphModelClients modelClients() {
        ExternalServicesHolder holder = ExternalServicesHolder.INSTANCE;
        return new GraphModelClients(
                new com.kwiki.graph.config.GraphModelProperties(null, null), holder.external);
    }

    /** 共享的问答模型配置，保证端点可用且不重复构造。 */
    private enum ExternalServicesHolder {
        INSTANCE;
        final com.kwiki.infrastructure.config.ExternalServicesProperties external =
                new com.kwiki.infrastructure.config.ExternalServicesProperties(
                        new com.kwiki.infrastructure.config.ExternalServicesProperties.ContentCenter(
                                "https://files.example.com", "t", null, null,
                                Duration.ofSeconds(5), Duration.ofSeconds(60)),
                        new com.kwiki.infrastructure.config.ExternalServicesProperties.Elasticsearch(
                                null, null, null),
                        new com.kwiki.infrastructure.config.ExternalServicesProperties.AnswerLlm(
                                "https://llm.example.com/v1", "key", "answer-model",
                                Duration.ofSeconds(30)),
                        new com.kwiki.infrastructure.config.ExternalServicesProperties.QwenEmbedding(
                                "https://e.example.com", "k", "m", 1024, Duration.ofSeconds(30)),
                        com.kwiki.infrastructure.config.ExternalServicesProperties.unusedVisionModel());
    }

    @Test
    void 缓存命中时不调用插件也不入队抽取任务() {
        GraphSourceChunk source = child().toSourceChunk(5);
        GraphExtractionResult cached = new GraphExtractionResult(source,
                List.of(new GraphEntity("e1", "Kwiki", GraphEntityType.PRODUCT, List.of())),
                List.of(), "kwiki-extractor-1", GraphExtractionPrompt.VERSION,
                "entity-linking-v1");
        GraphExtractionCache cache = mock(GraphExtractionCache.class);
        when(cache.findReady(any(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(cached));
        GraphExtractionJobPlanner planner = mock(GraphExtractionJobPlanner.class);
        List<GraphEntity> formal = List.of(new GraphEntity("ent:1", "Kwiki",
                GraphEntityType.PRODUCT, List.of()));
        ExtractionStage stage = stage(kbIdListing(), registryOf(pluginThrowing()), cache,
                planner, (kbId, candidates, context, version) -> formal);
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false,
                progress -> {
                });
        RunArtifacts artifacts = new RunArtifacts();

        stage.execute(ctx, artifacts);

        verify(planner, never()).plan(any(GraphExtractionJobSpec.class), eq(true), eq(true));
        assertThat(artifacts.entities).hasSize(1);
        assertThat(artifacts.entities.get(0).entityId()).isEqualTo("ent:1");
        assertThat(artifacts.extractedEntityIds).containsKey(source.sourceChunkId());
    }

    @Test
    void 缓存未命中时入队调用插件写回并改写为正式实体id() {
        GraphExtractionCache cache = mock(GraphExtractionCache.class);
        when(cache.findReady(any(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        GraphExtractionJobPlanner planner = mock(GraphExtractionJobPlanner.class);
        when(planner.plan(any(GraphExtractionJobSpec.class), eq(true), eq(true)))
                .thenReturn(Optional.of(77L));
        GraphSourceChunk source = child().toSourceChunk(5);
        GraphEntity temp = new GraphEntity("e1", "Kwiki", GraphEntityType.PRODUCT, List.of());
        GraphEntity temp2 = new GraphEntity("e2", "ArcadeDB", GraphEntityType.TECHNOLOGY, List.of());
        GraphRelation relation = new GraphRelation("r1", "e1", GraphPredicate.USES, "e2", 0.9,
                List.of(new GraphSourceRef(source.sourceChunkId(), 0, 5)));
        GraphExtractionResult pluginResult = new GraphExtractionResult(source,
                List.of(temp, temp2), List.of(relation), "kwiki-extractor-1",
                GraphExtractionPrompt.VERSION, "entity-linking-v1");
        GraphExtractorPlugin plugin = new GraphExtractorPlugin() {
            @Override
            public String id() {
                return "kwiki-extractor-1";
            }

            @Override
            public String label() {
                return "x";
            }

            @Override
            public String promptVersion() {
                return GraphExtractionPrompt.VERSION;
            }

            @Override
            public GraphExtractionResult extract(com.kwiki.graph.GraphExtractionInput input) {
                return pluginResult;
            }
        };
        List<GraphEntity> formal = List.of(
                new GraphEntity("ent:1", "Kwiki", GraphEntityType.PRODUCT, List.of()),
                new GraphEntity("ent:2", "ArcadeDB", GraphEntityType.TECHNOLOGY, List.of()));
        ExtractionStage stage = stage(kbIdListing(), registryOf(plugin), cache, planner,
                (kbId, candidates, context, version) -> formal);
        List<RunProgress> progressValues = new CopyOnWriteArrayList<>();
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false, progressValues::add);
        RunArtifacts artifacts = new RunArtifacts();

        stage.execute(ctx, artifacts);

        verify(cache).markReady(eq(77L), any());
        // 临时 id 全部改写为正式 id，关系身份重算
        assertThat(artifacts.entities).extracting(GraphEntity::entityId)
                .containsExactlyInAnyOrder("ent:1", "ent:2");
        assertThat(artifacts.relations).hasSize(1);
        GraphRelation rewritten = artifacts.relations.get(0);
        assertThat(rewritten.sourceEntityId()).isEqualTo("ent:1");
        assertThat(rewritten.targetEntityId()).isEqualTo("ent:2");
        assertThat(rewritten.relationId()).isEqualTo(sha256(
                source.sourceChunkId() + "|ent:1|USES|ent:2"));
        assertThat(artifacts.extractedEntityIds.get(source.sourceChunkId()))
                .containsExactly("ent:1", "ent:2");
        assertThat(progressValues).isNotEmpty();
        assertThat(progressValues.get(progressValues.size() - 1).sourcesTotal()).isEqualTo(1);
        assertThat(progressValues.get(progressValues.size() - 1).sourcesExtracted()).isEqualTo(1);
    }

    @Test
    void 单块结构校验失败为永久失败并记录缓存失败() {
        GraphExtractionCache cache = mock(GraphExtractionCache.class);
        when(cache.findReady(any(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        GraphExtractionJobPlanner planner = mock(GraphExtractionJobPlanner.class);
        when(planner.plan(any(GraphExtractionJobSpec.class), eq(true), eq(true)))
                .thenReturn(Optional.of(77L));
        // 版本不匹配 → GraphExtractionValidator 失败
        GraphSourceChunk source = child().toSourceChunk(5);
        GraphExtractionResult badResult = new GraphExtractionResult(source, List.of(), List.of(),
                "other-extractor", GraphExtractionPrompt.VERSION, "entity-linking-v1");
        GraphExtractorPlugin plugin = new GraphExtractorPlugin() {
            @Override
            public String id() {
                return "kwiki-extractor-1";
            }

            @Override
            public String label() {
                return "x";
            }

            @Override
            public String promptVersion() {
                return GraphExtractionPrompt.VERSION;
            }

            @Override
            public GraphExtractionResult extract(com.kwiki.graph.GraphExtractionInput input) {
                return badResult;
            }
        };
        ExtractionStage stage = stage(kbIdListing(), registryOf(plugin), cache, planner,
                (kbId, candidates, context, version) -> candidates);
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false, progress -> {
        });

        assertThatThrownBy(() -> stage.execute(ctx, new RunArtifacts()))
                .isInstanceOfSatisfying(GraphBuildFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("EXTRACTION_INVALID");
                    assertThat(failure.retryable()).isFalse();
                });
        verify(cache).markFailed(eq(77L), eq("EXTRACTION_INVALID"), anyString());
    }

    @Test
    void 取消时抛出CancellationException退出() {
        GraphExtractionCache cache = mock(GraphExtractionCache.class);
        when(cache.findReady(any(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        GraphExtractionJobPlanner planner = mock(GraphExtractionJobPlanner.class);
        GraphExtractorPlugin plugin = pluginThrowing();
        ExtractionStage stage = stage(kbIdListing(), registryOf(plugin), cache, planner,
                (kbId, candidates, context, version) -> candidates);
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> true, progress -> {
        });

        assertThatThrownBy(() -> stage.execute(ctx, new RunArtifacts()))
                .isInstanceOf(CancellationException.class);
    }

    private static GraphSourceChunkListingPort kbIdListing() {
        AtomicInteger calls = new AtomicInteger();
        return (chunkPhysicalIndex, kbId, maxResults) -> {
            calls.incrementAndGet();
            return List.of(child());
        };
    }

    private static GraphExtractorRegistry registryOf(GraphExtractorPlugin plugin) {
        return new GraphExtractorRegistry(List.of(plugin));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
