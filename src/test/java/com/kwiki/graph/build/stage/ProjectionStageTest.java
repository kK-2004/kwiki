package com.kwiki.graph.build.stage;

import com.kwiki.graph.GraphCapacityGuard;
import com.kwiki.graph.GraphWriteRequest;
import com.kwiki.graph.GraphWriteResult;
import com.kwiki.graph.KnowledgeGraphStore;
import com.kwiki.graph.UnweightedGraphProjection;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.build.RunArtifacts;
import com.kwiki.graph.build.RunContext;
import com.kwiki.graph.config.GraphBuildProperties;
import com.kwiki.graph.config.GraphProperties;
import com.kwiki.graph.persistence.GraphBuildRunRecord;
import com.kwiki.graph.persistence.GraphBuildStage;
import com.kwiki.graph.persistence.GraphBuildState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 投影阶段：容量守卫、投影生成与 ArcadeDB 写入。 */
class ProjectionStageTest {

    private static GraphBuildRunRecord run() {
        return new GraphBuildRunRecord(12L, 9L, 7L, 5, "kwiki-chunks-v5", 6L,
                "kwiki-communities-v6-kb7", 42L, "kwiki-schema-1", "entity-linking-v1",
                8L, 2L, GraphBuildState.PROJECTING, GraphBuildStage.PROJECTING, 3L,
                "owner-a", null, 0L, "kwiki-extractor-1", "arcadedb-leiden-1",
                "kwiki-summarizer-1");
    }

    private static GraphProperties graphProperties(int maxEntities) {
        return new GraphProperties(true, com.kwiki.graph.GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED,
                "0 0 2 * * *", "Asia/Shanghai", Duration.ofMinutes(10), 2,
                Duration.ofMinutes(30), new GraphProperties.Capacity(maxEntities, 250_000,
                1_000_000, 5_000, 20_000, 100_000, 2, 50, 500));
    }

    @Test
    void 正常路径生成投影并按隔离令牌写入() {
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        List<com.kwiki.graph.GraphWriteRequest> writes = new java.util.ArrayList<>();
        when(store.write(any(GraphWriteRequest.class))).thenAnswer(invocation -> {
            writes.add(invocation.getArgument(0));
            return new GraphWriteResult(1, 1, 1, 1, false);
        });
        ProjectionStage stage = new ProjectionStage(graphProperties(50_000), store,
                new GraphBuildProperties(Duration.ofSeconds(10), 2, Duration.ofMinutes(2),
                        1, Duration.ZERO, 100, 50));
        RunArtifacts artifacts = new RunArtifacts();
        artifacts.entities = List.of(
                new com.kwiki.graph.GraphEntity("a", "A", com.kwiki.graph.GraphEntityType.CONCEPT, List.of()),
                new com.kwiki.graph.GraphEntity("b", "B", com.kwiki.graph.GraphEntityType.CONCEPT, List.of()));
        artifacts.relations = List.of();
        artifacts.sources = List.of();
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false, progress -> {
        });

        stage.execute(ctx, artifacts);

        assertThat(artifacts.projection).isNotNull();
        assertThat(artifacts.projection.entityIds()).containsExactly("a", "b");
        assertThat(writes).hasSize(1);
        assertThat(writes.get(0).fencingToken()).isEqualTo(3L);
        assertThat(writes.get(0).graphVersion()).isEqualTo(42L);
    }

    @Test
    void 实体超过容量上限时永久失败并给出中文原因() {
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        ProjectionStage stage = new ProjectionStage(graphProperties(2), store,
                new GraphBuildProperties(Duration.ofSeconds(10), 2, Duration.ofMinutes(2),
                        1, Duration.ZERO, 100, 50));
        RunArtifacts artifacts = new RunArtifacts();
        artifacts.entities = List.of(
                new com.kwiki.graph.GraphEntity("a", "A", com.kwiki.graph.GraphEntityType.CONCEPT, List.of()),
                new com.kwiki.graph.GraphEntity("b", "B", com.kwiki.graph.GraphEntityType.CONCEPT, List.of()),
                new com.kwiki.graph.GraphEntity("c", "C", com.kwiki.graph.GraphEntityType.CONCEPT, List.of()));
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false, progress -> {
        });

        assertThatThrownBy(() -> stage.execute(ctx, artifacts))
                .isInstanceOfSatisfying(GraphBuildFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("CAPACITY_EXCEEDED_ENTITIES");
                    assertThat(failure.getMessage()).contains("实体数超过上限 2");
                    assertThat(failure.retryable()).isFalse();
                });
    }
}
