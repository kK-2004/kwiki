package com.kwiki.graph.build.stage;

import com.kwiki.graph.CommunityDetectionResult;
import com.kwiki.graph.CommunityMembership;
import com.kwiki.graph.CommunitySummary;
import com.kwiki.graph.GraphAlgorithmMode;
import com.kwiki.graph.CommunityIndexWriteRequest;
import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphEntityType;
import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.build.RunArtifacts;
import com.kwiki.graph.build.RunContext;
import com.kwiki.graph.config.GraphBuildProperties;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.graph.persistence.GraphBuildRunRecord;
import com.kwiki.graph.persistence.GraphBuildStage;
import com.kwiki.graph.persistence.GraphBuildState;
import com.kwiki.graph.persistence.GraphSnapshotIdentity;
import com.kwiki.graph.persistence.GraphSourceManifest;
import com.kwiki.graph.plugin.CommunitySummarizerPlugin;
import com.kwiki.graph.plugin.CommunitySummarizerRegistry;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import com.kwiki.infrastructure.elasticsearch.CommunityIndexWriter;
import com.kwiki.infrastructure.elasticsearch.EntityMappingBackfillService;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.EntityLinkingStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 写索引阶段：清单与快照落库、社区索引批写、子块实体回填与计数更新。 */
class IndexingStageTest {

    private static final String CONTENT = "Kwiki uses ArcadeDB.";

    private static GraphBuildRunRecord run() {
        return new GraphBuildRunRecord(12L, 9L, 7L, 5, "kwiki-chunks-v5", 6L,
                "kwiki-communities-v6-kb7", 42L, "kwiki-schema-1", "entity-linking-v1",
                8L, 2L, GraphBuildState.INDEXING, GraphBuildStage.INDEXING, 3L,
                "owner-a", null, 0L, "kwiki-extractor-1", "arcadedb-leiden-1",
                "kwiki-summarizer-1");
    }

    private static GraphBuildProperties buildProperties() {
        return new GraphBuildProperties(Duration.ofSeconds(10), 2, Duration.ofMinutes(2),
                1, Duration.ZERO, 100, 50);
    }

    private static CommunitySummarizerPlugin summarizer() {
        return new CommunitySummarizerPlugin() {
            @Override
            public String id() {
                return "kwiki-summarizer-1";
            }

            @Override
            public String label() {
                return "x";
            }

            @Override
            public String promptVersion() {
                return "community-summary-v1";
            }

            @Override
            public String modelName() {
                return "summary-model";
            }

            @Override
            public CommunitySummary summarize(com.kwiki.graph.CommunitySummaryInput input) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static SearchIndexVersionRepository versions() {
        SearchIndexVersionRepository repository = mock(SearchIndexVersionRepository.class);
        SearchIndexVersion version = mock(SearchIndexVersion.class);
        when(version.editableConfig()).thenReturn(new EditableIndexConfig("parser-1",
                "chunker-1", "provider", "embed-model", 1024, "kwiki-schema-1"));
        when(repository.findByVersionNumber(5)).thenReturn(Optional.of(version));
        return repository;
    }

    private static RunArtifacts artifacts() {
        GraphSourceChunk source = new GraphSourceChunk(7L, "PAGE", 1L, 3L, 2L, 5,
                "parser-1", "chunker-1", "kb7:page:1:chunk:0",
                "hash".repeat(16).substring(0, 64));
        RunArtifacts artifacts = new RunArtifacts();
        artifacts.sources = List.of(source);
        artifacts.childrenBySourceChunkId.put(source.sourceChunkId(),
                new ChunkHit("kb7:page:1:chunk:0", "kb7:page:1", 7L, "PAGE", 1L, 3L, "h",
                        0, CONTENT.length(), CONTENT, List.of(), source.sourceChunkId(),
                        List.of(), "entity-linking-v1", EntityLinkingStatus.PENDING));
        artifacts.extractedEntityIds.put(source.sourceChunkId(), List.of("ent:1"));
        artifacts.detection = new CommunityDetectionResult(
                GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED, "25.1",
                List.of(new CommunityMembership(7L, 42L, "ent:1", "c0")));
        artifacts.summaries.put("c0", new CommunitySummary("标题", "正文", List.of(),
                List.of(), List.of(), List.of(), List.of()));
        artifacts.entities = List.of(new GraphEntity("ent:1", "Kwiki",
                GraphEntityType.PRODUCT, List.of()));
        return artifacts;
    }

    private IndexingStage stage(GraphBuildRepository repository,
                                CommunityIndexWriter writer,
                                EntityMappingBackfillService backfill) {
        return new IndexingStage(new com.kwiki.graph.persistence.GraphSourceManifestBuilder(),
                repository, writer, backfill,
                new CommunitySummarizerRegistry(List.of(summarizer())), versions(),
                buildProperties());
    }

    @Test
    void 正常路径落库清单快照并回填子块() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        when(repository.persistManifest(any(GraphSourceManifest.class))).thenReturn(77L);
        when(repository.createSnapshot(any(), anyLong(), anyString(), any(GraphSnapshotIdentity.class)))
                .thenReturn(91L);
        CommunityIndexWriter writer = mock(CommunityIndexWriter.class);
        when(writer.write(any())).thenReturn(new CommunityIndexWriter.WriteResult(1, true));
        EntityMappingBackfillService backfill = mock(EntityMappingBackfillService.class);
        when(backfill.backfill(any())).thenReturn(
                EntityMappingBackfillService.Outcome.UPDATED);
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false, progress -> {
        });
        RunArtifacts artifacts = artifacts();

        stage(repository, writer, backfill).execute(ctx, artifacts);

        assertThat(artifacts.manifestId).isEqualTo(77L);
        assertThat(artifacts.snapshotId).isEqualTo(91L);
        assertThat(artifacts.communityIndexVerified).isTrue();
        verify(repository).updateSnapshotCounts(91L, 1, 0, 1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommunityIndexWriteRequest>> writeCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(writer).write(writeCaptor.capture());
        CommunityIndexWriteRequest request = writeCaptor.getValue().get(0);
        assertThat(request.communityId()).isEqualTo("c0");
        assertThat(request.sourceManifestId()).isEqualTo("77");
        assertThat(request.summaryModel()).isEqualTo("summary-model");
        assertThat(request.summaryPromptVersion()).isEqualTo("community-summary-v1");
        assertThat(request.embeddingModel()).isEqualTo("embed-model");
        assertThat(request.embeddingDimensions()).isEqualTo(1024);
        verify(backfill).backfill(any(EntityMappingBackfillService.Request.class));
        assertThat(artifacts.observations).hasSize(1);
        assertThat(artifacts.observations.values().iterator().next().status())
                .isEqualTo(EntityLinkingStatus.READY);
    }

    @Test
    void 回填身份不匹配时永久失败() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        when(repository.persistManifest(any(GraphSourceManifest.class))).thenReturn(77L);
        when(repository.createSnapshot(any(), anyLong(), anyString(), any(GraphSnapshotIdentity.class)))
                .thenReturn(91L);
        CommunityIndexWriter writer = mock(CommunityIndexWriter.class);
        when(writer.write(any())).thenReturn(new CommunityIndexWriter.WriteResult(1, true));
        EntityMappingBackfillService backfill = mock(EntityMappingBackfillService.class);
        when(backfill.backfill(any())).thenReturn(
                EntityMappingBackfillService.Outcome.SOURCE_MISMATCH);
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false, progress -> {
        });

        assertThatThrownBy(() -> stage(repository, writer, backfill).execute(ctx, artifacts()))
                .isInstanceOfSatisfying(GraphBuildFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("ENTITY_BACKFLOW_MISMATCH");
                    assertThat(failure.retryable()).isFalse();
                });
        // 回填失败时不更新计数
        verify(repository, never()).updateSnapshotCounts(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void 社区索引读回校验未通过时按可重试失败处理() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        when(repository.persistManifest(any(GraphSourceManifest.class))).thenReturn(77L);
        when(repository.createSnapshot(any(), anyLong(), anyString(), any(GraphSnapshotIdentity.class)))
                .thenReturn(91L);
        CommunityIndexWriter writer = mock(CommunityIndexWriter.class);
        when(writer.write(any())).thenReturn(new CommunityIndexWriter.WriteResult(1, false));
        EntityMappingBackfillService backfill = mock(EntityMappingBackfillService.class);
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false, progress -> {
        });

        assertThatThrownBy(() -> stage(repository, writer, backfill).execute(ctx, artifacts()))
                .isInstanceOfSatisfying(GraphBuildFailure.class,
                        failure -> assertThat(failure.retryable()).isTrue());
        verify(backfill, never()).backfill(any());
    }

    @Test
    void 摘要为空时跳过社区索引写入() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        when(repository.persistManifest(any(GraphSourceManifest.class))).thenReturn(77L);
        when(repository.createSnapshot(any(), anyLong(), anyString(), any(GraphSnapshotIdentity.class)))
                .thenReturn(91L);
        CommunityIndexWriter writer = mock(CommunityIndexWriter.class);
        EntityMappingBackfillService backfill = mock(EntityMappingBackfillService.class);
        when(backfill.backfill(any())).thenReturn(EntityMappingBackfillService.Outcome.UPDATED);
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false, progress -> {
        });
        RunArtifacts artifacts = artifacts();
        artifacts.summaries.clear();

        stage(repository, writer, backfill).execute(ctx, artifacts);

        verify(writer, never()).write(any());
        verify(repository).updateSnapshotCounts(91L, 1, 0, 0);
    }
}
