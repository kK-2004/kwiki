package com.kwiki.graph.build.stage;

import com.kwiki.graph.CommunityDetectionResult;
import com.kwiki.graph.CommunityMembership;
import com.kwiki.graph.CommunitySummary;
import com.kwiki.graph.GraphAlgorithmMode;
import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphEntityType;
import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.graph.GraphSnapshot;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.build.RunArtifacts;
import com.kwiki.graph.build.RunContext;
import com.kwiki.graph.config.GraphBuildProperties;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.graph.persistence.GraphBuildRunRecord;
import com.kwiki.graph.persistence.GraphBuildStage;
import com.kwiki.graph.persistence.GraphBuildState;
import com.kwiki.graph.persistence.GraphSnapshotAutoPublishService;
import com.kwiki.graph.persistence.GraphSnapshotEntry;
import com.kwiki.graph.persistence.GraphSnapshotSealingService;
import com.kwiki.graph.persistence.GraphSnapshotState;
import com.kwiki.graph.persistence.GraphSnapshotValidationReport;
import com.kwiki.graph.persistence.GraphSnapshotValidationService;
import com.kwiki.graph.persistence.GraphSourceManifest;
import com.kwiki.graph.plugin.CommunitySummarizerPlugin;
import com.kwiki.graph.plugin.CommunitySummarizerRegistry;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 校验阶段：报告生成、封存与自动发布衔接。 */
class ValidationStageTest {

    private static GraphBuildRunRecord run() {
        return new GraphBuildRunRecord(12L, 9L, 7L, 5, "kwiki-chunks-v5", 6L,
                "kwiki-communities-v6-kb7", 42L, "kwiki-schema-1", "entity-linking-v1",
                8L, 2L, GraphBuildState.VALIDATING, GraphBuildStage.VALIDATING, 3L,
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
        artifacts.detection = new CommunityDetectionResult(
                GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED, "25.1",
                List.of(new CommunityMembership(7L, 42L, "ent:1", "c0")));
        artifacts.summaries.put("c0", new CommunitySummary("标题", "正文", List.of(),
                List.of(), List.of(), List.of(), List.of()));
        artifacts.observations.put(source.sourceChunkId(),
                new com.kwiki.graph.persistence.EntityMappingObservation(
                        source.sourceChunkId(), List.of("ent:1"), "entity-linking-v1",
                        com.kwiki.rag.retrieval.EntityLinkingStatus.READY));
        com.kwiki.graph.persistence.GraphSourceManifestBuilder builder =
                new com.kwiki.graph.persistence.GraphSourceManifestBuilder();
        artifacts.manifest = builder.build(12L, 7L, 5, "entity-linking-v1", 8L, 2L, 0L, true,
                List.of(source), java.util.Map.of(source.sourceChunkId(),
                        com.kwiki.graph.persistence.GraphSourceReadiness.READY));
        artifacts.extractedEntityIds.put(source.sourceChunkId(), List.of("ent:1"));
        artifacts.projection = com.kwiki.graph.UnweightedGraphProjectionBuilder.build(7L, 42L,
                List.of(new GraphEntity("ent:1", "Kwiki", GraphEntityType.PRODUCT, List.of())),
                List.of());
        artifacts.communityIndexVerified = true;
        artifacts.manifestId = 77L;
        artifacts.snapshotId = 91L;
        return artifacts;
    }

    @Test
    void 报告通过时封存为READY并触发自动发布判定() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        GraphSnapshot snapshot = new GraphSnapshot(91L, 7L, 42L, 5L, 6L, "kwiki-chunks-v5",
                "kwiki-communities-v6-kb7", "entity-linking-v1", "manifest-hash", 8L, 2L);
        when(repository.findSnapshotById(91L)).thenReturn(Optional.of(
                new GraphSnapshotEntry(snapshot, GraphSnapshotState.READY)));
        when(repository.sealSnapshotReady(eq(91L), any())).thenReturn(true);
        GraphSnapshotAutoPublishService autoPublish = mock(GraphSnapshotAutoPublishService.class);
        ValidationStage stage = new ValidationStage(new GraphSnapshotValidationService(),
                new GraphSnapshotSealingService(repository), autoPublish, repository,
                new CommunitySummarizerRegistry(List.of(summarizer())), versions());
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false, progress -> {
        });
        RunArtifacts artifacts = artifacts();

        stage.execute(ctx, artifacts);

        // 封存发生在校验报告持久化处
        verify(repository).sealSnapshotReady(eq(91L), contains("SOURCE_COVERAGE"));
        verify(autoPublish).publishIfAutoPublishEnabled(eq(12L), eq(snapshot),
                eq(GraphSnapshotState.READY), any());
    }

    @Test
    void 报告未通过时永久失败且不触发发布() {
        GraphBuildRepository repository = mock(GraphBuildRepository.class);
        // 封存失败：快照已被并发变更
        when(repository.sealSnapshotReady(anyLong(), any())).thenReturn(false);
        GraphSnapshotAutoPublishService autoPublish = mock(GraphSnapshotAutoPublishService.class);
        ValidationStage stage = new ValidationStage(new GraphSnapshotValidationService(),
                new GraphSnapshotSealingService(repository), autoPublish, repository,
                new CommunitySummarizerRegistry(List.of(summarizer())), versions());
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false, progress -> {
        });
        RunArtifacts artifacts = artifacts();
        artifacts.summaries.clear(); // 缺失摘要 → SUMMARIES_COMPLETE 失败

        assertThatThrownBy(() -> stage.execute(ctx, artifacts))
                .isInstanceOfSatisfying(GraphBuildFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("SNAPSHOT_VALIDATION_FAILED");
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.getMessage()).contains("SUMMARIES_COMPLETE");
                });
        verify(repository).persistValidationReport(eq(91L), any());
        verify(autoPublish, never()).publishIfAutoPublishEnabled(anyLong(), any(), any(), any());
    }
}
