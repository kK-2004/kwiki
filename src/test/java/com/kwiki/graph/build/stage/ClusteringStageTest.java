package com.kwiki.graph.build.stage;

import com.kwiki.graph.CommunityDetectionInput;
import com.kwiki.graph.CommunityDetectionResult;
import com.kwiki.graph.CommunityMembership;
import com.kwiki.graph.GraphAlgorithmMode;
import com.kwiki.graph.KnowledgeGraphStore;
import com.kwiki.graph.UnweightedGraphProjection;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.build.RunArtifacts;
import com.kwiki.graph.build.RunContext;
import com.kwiki.graph.config.GraphBuildProperties;
import com.kwiki.graph.config.GraphClusteringProperties;
import com.kwiki.graph.config.GraphProperties;
import com.kwiki.graph.persistence.GraphBuildRunRecord;
import com.kwiki.graph.persistence.GraphBuildStage;
import com.kwiki.graph.persistence.GraphBuildState;
import com.kwiki.graph.plugin.CommunityDetectorPlugin;
import com.kwiki.graph.plugin.CommunityDetectorRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 聚类阶段：参数传递、结果保存与社区数量上限。 */
class ClusteringStageTest {

    private final KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);

    private static GraphBuildRunRecord run() {
        return new GraphBuildRunRecord(12L, 9L, 7L, 5, "kwiki-chunks-v5", 6L,
                "kwiki-communities-v6-kb7", 42L, "kwiki-schema-1", "entity-linking-v1",
                8L, 2L, GraphBuildState.CLUSTERING, GraphBuildStage.CLUSTERING, 3L,
                "owner-a", null, 0L, "kwiki-extractor-1", "arcadedb-leiden-1",
                "kwiki-summarizer-1");
    }

    @Test
    void 按配置参数调用检测插件并保存结果() {
        List<CommunityDetectionInput> inputs = new java.util.ArrayList<>();
        CommunityDetectorPlugin detector = new CommunityDetectorPlugin() {
            @Override
            public String id() {
                return "arcadedb-leiden-1";
            }

            @Override
            public String label() {
                return "x";
            }

            @Override
            public CommunityDetectionResult detect(CommunityDetectionInput input) {
                inputs.add(input);
                return new CommunityDetectionResult(GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                        "25.1", List.of(new CommunityMembership(7L, 42L, "a", "c0")));
            }
        };
        ClusteringStage stage = new ClusteringStage(
                new CommunityDetectorRegistry(List.of(detector)),
                new GraphProperties(true, GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                        "0 0 2 * * *", "Asia/Shanghai", false, Duration.ofMinutes(10), 2,
                        Duration.ofMinutes(30), GraphProperties.Capacity.defaults()),
                new GraphClusteringProperties(25, 1.5),
                new GraphBuildProperties(Duration.ofSeconds(10), 2, Duration.ofMinutes(2),
                        1, Duration.ZERO, 100, 50), store);
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false, progress -> {
        });
        RunArtifacts artifacts = new RunArtifacts();
        artifacts.projection = new UnweightedGraphProjection(7L, 42L, List.of("a"), List.of(),
                "projection-hash");

        stage.execute(ctx, artifacts);

        assertThat(inputs).hasSize(1);
        CommunityDetectionInput input = inputs.get(0);
        assertThat(input.maxIterations()).isEqualTo(25);
        assertThat(input.resolution()).isEqualTo(1.5);
        assertThat(input.projectionHash()).isEqualTo("projection-hash");
        assertThat(input.runId()).isEqualTo("12");
        assertThat(input.fencingToken()).isEqualTo(3L);
        assertThat(artifacts.detection).isNotNull();
        assertThat(artifacts.detection.memberships()).hasSize(1);
        assertThat(input.projection()).isSameAs(artifacts.projection);
        // 社区归属回写到图库实体
        verify(store).assignCommunities(7L, 42L, artifacts.detection.memberships());
    }

    @Test
    void 社区数超过摘要上限时永久失败() {
        CommunityDetectorPlugin detector = new CommunityDetectorPlugin() {
            @Override
            public String id() {
                return "arcadedb-leiden-1";
            }

            @Override
            public String label() {
                return "x";
            }

            @Override
            public CommunityDetectionResult detect(CommunityDetectionInput input) {
                List<CommunityMembership> memberships = IntStream.range(0, 3)
                        .mapToObj(i -> new CommunityMembership(7L, 42L, "e" + i, "c" + i))
                        .toList();
                return new CommunityDetectionResult(
                        GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED, "25.1", memberships);
            }
        };
        ClusteringStage stage = new ClusteringStage(
                new CommunityDetectorRegistry(List.of(detector)),
                new GraphProperties(true, GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                        "0 0 2 * * *", "Asia/Shanghai", false, Duration.ofMinutes(10), 2,
                        Duration.ofMinutes(30), new GraphProperties.Capacity(50_000, 250_000,
                        1_000_000, 2, 20_000, 100_000, 2, 50, 500)),
                new GraphClusteringProperties(10, 1.0),
                new GraphBuildProperties(Duration.ofSeconds(10), 2, Duration.ofMinutes(2),
                        1, Duration.ZERO, 100, 2), store);
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false, progress -> {
        });
        RunArtifacts artifacts = new RunArtifacts();
        artifacts.projection = new UnweightedGraphProjection(7L, 42L, List.of("a"), List.of(),
                "projection-hash");

        assertThatThrownBy(() -> stage.execute(ctx, artifacts))
                .isInstanceOfSatisfying(GraphBuildFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("CAPACITY_EXCEEDED_COMMUNITIES");
                    assertThat(failure.retryable()).isFalse();
                });
        verifyNoInteractions(store);
    }
}
