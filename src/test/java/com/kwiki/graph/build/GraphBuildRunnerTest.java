package com.kwiki.graph.build;

import com.kwiki.graph.GraphSourceInventoryPort;
import com.kwiki.graph.build.stage.ClusteringStage;
import com.kwiki.graph.build.stage.ExtractionStage;
import com.kwiki.graph.build.stage.IndexingStage;
import com.kwiki.graph.build.stage.ProjectionStage;
import com.kwiki.graph.build.stage.SummarizationStage;
import com.kwiki.graph.build.stage.ValidationStage;
import com.kwiki.graph.persistence.GraphBuildMetrics;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.graph.persistence.GraphBuildRunCoordinator;
import com.kwiki.graph.persistence.GraphBuildRunRecord;
import com.kwiki.graph.persistence.GraphBuildStage;
import com.kwiki.graph.persistence.GraphBuildState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 单任务推进：阶段顺序、断点、失败分类、取消与恢复。 */
class GraphBuildRunnerTest {

    private static GraphBuildRunRecord run(GraphBuildState state) {
        return new GraphBuildRunRecord(12L, 9L, 7L, 5, "kwiki-chunks-v5", 6L,
                "kwiki-communities-v6-kb7", 42L, "kwiki-schema-1", "entity-linking-v1",
                8L, 2L, state, GraphBuildStage.QUEUED, 3L, "owner-a", null, 0L,
                "kwiki-extractor-1", "arcadedb-leiden-1", "kwiki-summarizer-1");
    }

    private GraphBuildRepository repository;
    private GraphBuildRunCoordinator coordinator;
    private ExtractionStage extraction;
    private ProjectionStage projection;
    private ClusteringStage clustering;
    private SummarizationStage summarization;
    private IndexingStage indexing;
    private ValidationStage validation;
    private GraphBuildRunner runner;

    @BeforeEach
    void setUp() {
        repository = mock(GraphBuildRepository.class);
        coordinator = mock(GraphBuildRunCoordinator.class);
        extraction = mock(ExtractionStage.class);
        projection = mock(ProjectionStage.class);
        clustering = mock(ClusteringStage.class);
        summarization = mock(SummarizationStage.class);
        indexing = mock(IndexingStage.class);
        validation = mock(ValidationStage.class);
        GraphSourceInventoryPort inventory = mock(GraphSourceInventoryPort.class);
        when(inventory.currentEpochs(7L)).thenReturn(new long[]{8L, 2L});
        runner = new GraphBuildRunner(coordinator, repository, inventory,
                mock(GraphBuildMetrics.class), extraction, projection, clustering,
                summarization, indexing, validation);
    }

    @Test
    void 阶段内模型调用失败按租约身份累计到子任务() {
        org.mockito.Mockito.doAnswer(invocation -> {
            RunContext ctx = invocation.getArgument(0);
            ctx.modelAttempts().attemptFailed(
                    GraphBuildFailure.retryable(GraphBuildFailure.MODEL_TIMEOUT, "超时"), true);
            ctx.modelAttempts().attemptFailed(
                    GraphBuildFailure.retryable("MODEL_UNAVAILABLE", "5xx"), false);
            return null;
        }).when(extraction).execute(any(), any());

        runner.run(run(GraphBuildState.QUEUED), "owner-a", 3L, () -> false);

        verify(repository).recordModelAttemptFailure(12L, "owner-a", 3L, 1, 1);
        verify(repository).recordModelAttemptFailure(12L, "owner-a", 3L, 0, 0);
    }

    @Test
    void 从QUEUED按顺序推进全部阶段并写断点() {
        runner.run(run(GraphBuildState.QUEUED), "owner-a", 3L, () -> false);

        InOrder stages = inOrder(extraction, projection, clustering, summarization,
                indexing, validation);
        stages.verify(extraction).execute(any(), any());
        stages.verify(projection).execute(any(), any());
        stages.verify(clustering).execute(any(), any());
        stages.verify(summarization).execute(any(), any());
        stages.verify(indexing).execute(any(), any());
        stages.verify(validation).execute(any(), any());

        InOrder checkpoints = inOrder(coordinator);
        checkpoints.verify(coordinator).checkpoint(any(), eq("owner-a"), eq(3L),
                eq(GraphBuildState.EXTRACTING), eq(GraphBuildStage.EXTRACTING), eq(0L));
        checkpoints.verify(coordinator).checkpoint(any(), eq("owner-a"), eq(3L),
                eq(GraphBuildState.PROJECTING), eq(GraphBuildStage.PROJECTING), eq(0L));
        checkpoints.verify(coordinator).checkpoint(any(), eq("owner-a"), eq(3L),
                eq(GraphBuildState.SUMMARIZING), eq(GraphBuildStage.SUMMARIZING), eq(0L));
        checkpoints.verify(coordinator).checkpoint(any(), eq("owner-a"), eq(3L),
                eq(GraphBuildState.VALIDATING), eq(GraphBuildStage.VALIDATING), eq(0L));
        // VALIDATING 通过 → READY 终态断点
        checkpoints.verify(coordinator).checkpoint(any(), eq("owner-a"), eq(3L),
                eq(GraphBuildState.READY), eq(GraphBuildStage.READY), eq(0L));
        // 子任务开始执行时批次置为进行中，结束时再结算
        InOrder batchState = inOrder(repository);
        batchState.verify(repository).markBatchRunning(9L);
        batchState.verify(repository).settleBatch(9L);
    }

    @Test
    void 恢复时从当前状态对应的阶段继续() {
        runner.run(run(GraphBuildState.SUMMARIZING), "owner-a", 3L, () -> false);

        verify(extraction, never()).execute(any(), any());
        verify(projection, never()).execute(any(), any());
        verify(clustering, never()).execute(any(), any());
        verify(summarization).execute(any(), any());
        verify(indexing).execute(any(), any());
        verify(validation).execute(any(), any());
        // INDEXING/VALIDATING 恢复时按 run_id 复用已有 BUILDING 快照
        verify(repository, never()).findBuildingSnapshot(12L);
    }

    @Test
    void 从INDEXING恢复时复用已有BUILDING快照() {
        when(repository.findBuildingSnapshot(12L)).thenReturn(java.util.Optional.of(
                new com.kwiki.graph.persistence.GraphBuildingSnapshot(91L, 77L, "hash")));

        runner.run(run(GraphBuildState.INDEXING), "owner-a", 3L, () -> false);

        verify(repository).findBuildingSnapshot(12L);
        verify(repository).settleBatch(9L);
    }

    @Test
    void 永久失败置FAILED并收尾批次() {
        doThrow(GraphBuildFailure.permanent("EXTRACTION_INVALID", "抽取校验失败"))
                .when(extraction).execute(any(), any());

        runner.run(run(GraphBuildState.QUEUED), "owner-a", 3L, () -> false);

        verify(repository).failRun(eq(12L), eq("owner-a"), eq(3L), eq("EXTRACTION_INVALID"),
                contains("抽取校验失败"));
        verify(repository).settleBatch(9L);
        verify(projection, never()).execute(any(), any());
    }

    @Test
    void 取消后退出不写运行状态() {
        doThrow(new CancellationException("已取消")).when(extraction).execute(any(), any());

        runner.run(run(GraphBuildState.QUEUED), "owner-a", 3L, () -> false);

        verify(repository, never()).failRun(anyLong(), anyString(), anyLong(), anyString(),
                anyString());
        verify(repository, never()).markStale(anyLong(), anyString(), anyLong());
    }

    @Test
    void 来源epoch过期时标记STALE并收尾() {
        GraphSourceInventoryPort changed = mock(GraphSourceInventoryPort.class);
        when(changed.currentEpochs(7L)).thenReturn(new long[]{9L, 2L});
        runner = new GraphBuildRunner(coordinator, repository, changed,
                mock(GraphBuildMetrics.class), extraction, projection, clustering,
                summarization, indexing, validation);

        runner.run(run(GraphBuildState.QUEUED), "owner-a", 3L, () -> false);

        verify(repository).markStale(12L, "owner-a", 3L);
        verify(repository).settleBatch(9L);
        verify(projection, never()).execute(any(), any());
    }

    @Test
    void 续租失败后立即退出不再写状态() {
        runner.run(run(GraphBuildState.QUEUED), "owner-a", 3L, () -> true);

        verify(coordinator, never()).checkpoint(any(), anyString(), anyLong(), any(),
                any(), anyLong());
        verify(extraction, never()).execute(any(), any());
    }

    @Test
    void 已READY的子任务只做批次收尾() {
        runner.run(run(GraphBuildState.READY), "owner-a", 3L, () -> false);

        verify(extraction, never()).execute(any(), any());
        verify(coordinator, never()).checkpoint(any(), anyString(), anyLong(), any(),
                any(), anyLong());
        verify(repository).settleBatch(9L);
    }

    @Test
    void 未映射的活动状态按永久失败收尾() {
        runner.run(run(GraphBuildState.WAITING_FOR_CHUNKS), "owner-a", 3L, () -> false);

        verify(repository).failRun(eq(12L), eq("owner-a"), eq(3L), eq("UNSUPPORTED_STATE"),
                anyString());
        verify(repository).settleBatch(9L);
    }
}
