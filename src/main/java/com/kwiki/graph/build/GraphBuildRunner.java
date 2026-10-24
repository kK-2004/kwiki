package com.kwiki.graph.build;

import com.kwiki.graph.GraphSourceInventoryPort;
import com.kwiki.graph.build.stage.ClusteringStage;
import com.kwiki.graph.build.stage.ExtractionStage;
import com.kwiki.graph.build.stage.ProjectionStage;
import com.kwiki.graph.build.stage.SummarizationStage;
import com.kwiki.graph.build.stage.ValidationStage;
import com.kwiki.graph.build.stage.IndexingStage;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.graph.persistence.GraphBuildRunCoordinator;
import com.kwiki.graph.persistence.GraphBuildRunRecord;
import com.kwiki.graph.persistence.GraphBuildStage;
import com.kwiki.graph.persistence.GraphBuildState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * 单个子任务的阶段推进：每个阶段先写断点再执行，异常按分类落终态；
 * 崩溃恢复时其他实例在租约过期后从当前阶段继续，抽取命中缓存不重复调用模型。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphBuildRunner {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GraphBuildRunner.class);

    /** 从 QUEUED 开始的完整构建序列；VALIDATING 通过后进入 READY。 */
    private static final List<GraphBuildState> FULL_SEQUENCE = List.of(
            GraphBuildState.EXTRACTING, GraphBuildState.PROJECTING, GraphBuildState.CLUSTERING,
            GraphBuildState.SUMMARIZING, GraphBuildState.INDEXING, GraphBuildState.VALIDATING);

    private final GraphBuildRunCoordinator coordinator;
    private final GraphBuildRepository repository;
    private final GraphSourceInventoryPort sourceInventory;
    private final com.kwiki.graph.persistence.GraphBuildMetrics metrics;
    private final ExtractionStage extractionStage;
    private final ProjectionStage projectionStage;
    private final ClusteringStage clusteringStage;
    private final SummarizationStage summarizationStage;
    private final IndexingStage indexingStage;
    private final ValidationStage validationStage;

    public GraphBuildRunner(GraphBuildRunCoordinator coordinator,
                            GraphBuildRepository repository,
                            GraphSourceInventoryPort sourceInventory,
                            com.kwiki.graph.persistence.GraphBuildMetrics metrics,
                            ExtractionStage extractionStage,
                            ProjectionStage projectionStage,
                            ClusteringStage clusteringStage,
                            SummarizationStage summarizationStage,
                            IndexingStage indexingStage,
                            ValidationStage validationStage) {
        this.coordinator = coordinator;
        this.repository = repository;
        this.sourceInventory = sourceInventory;
        this.metrics = metrics;
        this.extractionStage = extractionStage;
        this.projectionStage = projectionStage;
        this.clusteringStage = clusteringStage;
        this.summarizationStage = summarizationStage;
        this.indexingStage = indexingStage;
        this.validationStage = validationStage;
    }

    /**
     * @param leaseLost 续租失败探针；为真时立即退出且不再写任何权威状态，
     *                  交由租约过期后的接管实例继续。
     */
    public void run(GraphBuildRunRecord run, String owner, long fencingToken,
                    BooleanSupplier leaseLost) {
        List<GraphBuildState> sequence;
        try {
            sequence = sequenceFrom(run.state());
        } catch (IllegalStateException unmapped) {
            repository.failRun(run.id(), owner, fencingToken, "UNSUPPORTED_STATE",
                    String.valueOf(unmapped.getMessage()));
            repository.settleBatch(run.batchId());
            return;
        }
        if (sequence == null) {
            // 已是终态（如发布后）：无需推进，只做批次收尾
            repository.settleBatch(run.batchId());
            return;
        }
        log.info("graph build run started run={} batch={} kb={} graphVersion={} chunkVersion={} from={} stages={}",
                run.id(), run.batchId(), run.kbId(), run.graphVersion(), run.chunkIndexVersion(),
                run.state(), sequence);
        Instant runStartedAt = Instant.now();
        repository.markBatchRunning(run.batchId());
        RunArtifacts artifacts = new RunArtifacts();
        if (run.state() == GraphBuildState.INDEXING
                || run.state() == GraphBuildState.VALIDATING) {
            // 恢复：复用崩溃前已创建的 BUILDING 快照与清单
            repository.findBuildingSnapshot(run.id()).ifPresent(existing -> {
                artifacts.snapshotId = existing.snapshotId();
                artifacts.manifestId = existing.manifestId();
                artifacts.manifestHash = existing.manifestHash();
            });
        }
        GraphBuildRunRecord current = run;
        for (GraphBuildState next : sequence) {
            if (leaseLost.getAsBoolean()) {
                return;
            }
            Instant startedAt = Instant.now();
            try {
                coordinator.checkpoint(current, owner, fencingToken, next,
                        stageOf(next), current.eventWatermark());
            } catch (IllegalStateException fencingLost) {
                // 隔离令牌已失效：立即停止，不写任何状态
                return;
            }
            if (leaseLost.getAsBoolean()) {
                return;
            }
            if (sourceEpochsChanged(run)) {
                metrics.recordFailure(stageOf(next).name());
                log.info("graph build run {} stale before {}: source epochs changed, will be rebuilt",
                        run.id(), next);
                repository.markStale(run.id(), owner, fencingToken);
                repository.settleBatch(run.batchId());
                return;
            }
            log.info("graph build run {} stage {} started", run.id(), next);
            try {
                stage(next).execute(ctx(run, owner, fencingToken, leaseLost), artifacts);
            } catch (CancellationException cancelled) {
                // 管理端取消：退出且不写任何状态
                log.info("graph build run {} cancelled during {} after {}ms", run.id(), next,
                        Duration.between(startedAt, Instant.now()).toMillis());
                return;
            } catch (GraphBuildFailure failure) {
                log.warn("graph build run {} failed in {} after {}ms: code={} retryable={} summary={}",
                        run.id(), next, Duration.between(startedAt, Instant.now()).toMillis(),
                        failure.code(), failure.retryable(), failure.getMessage());
                metrics.recordStage(stageOf(next).name(), Duration.between(startedAt,
                        Instant.now()), false);
                metrics.recordFailure(stageOf(next).name());
                repository.failRun(run.id(), owner, fencingToken, failure.code(),
                        String.valueOf(failure.getMessage()));
                repository.settleBatch(run.batchId());
                return;
            } catch (RuntimeException unexpected) {
                log.warn("graph build run {} failed unexpectedly in {} after {}ms", run.id(), next,
                        Duration.between(startedAt, Instant.now()).toMillis(), unexpected);
                metrics.recordStage(stageOf(next).name(), Duration.between(startedAt,
                        Instant.now()), false);
                metrics.recordFailure(stageOf(next).name());
                repository.failRun(run.id(), owner, fencingToken, "RUN_FAILURE",
                        unexpected.getClass().getSimpleName()
                                + (unexpected.getMessage() == null ? ""
                                : ": " + unexpected.getMessage()));
                repository.settleBatch(run.batchId());
                return;
            }
            metrics.recordStage(stageOf(next).name(), Duration.between(startedAt,
                    Instant.now()), true);
            log.info("graph build run {} stage {} finished in {}ms: {}", run.id(), next,
                    Duration.between(startedAt, Instant.now()).toMillis(), stageSummary(next, artifacts));
            current = withState(current, next);
        }
        // VALIDATING 通过 → READY（终态），批次收尾
        coordinator.checkpoint(current, owner, fencingToken, GraphBuildState.READY,
                GraphBuildStage.READY, current.eventWatermark());
        log.info("graph build run {} ready in {}ms: snapshot={} entities={} relations={} communities={}",
                run.id(), Duration.between(runStartedAt, Instant.now()).toMillis(), artifacts.snapshotId,
                artifacts.entities.size(), artifacts.relations.size(), artifacts.summaries.size());
        repository.settleBatch(run.batchId());
    }

    /** 阶段结束时的产物摘要，只含计数与标识，不含正文。 */
    static String stageSummary(GraphBuildState stage, RunArtifacts artifacts) {
        return switch (stage) {
            case EXTRACTING -> "sources=" + artifacts.sources.size()
                    + " cacheHits=" + artifacts.extractionCacheHits
                    + " entities=" + artifacts.entities.size()
                    + " relations=" + artifacts.relations.size();
            case PROJECTING -> "entities=" + artifacts.entities.size()
                    + " relations=" + artifacts.relations.size();
            case CLUSTERING -> "communities=" + (artifacts.detection == null ? 0
                    : artifacts.detection.memberships().stream()
                            .map(com.kwiki.graph.CommunityMembership::communityId).distinct().count())
                    + " members=" + (artifacts.detection == null ? 0 : artifacts.detection.memberships().size());
            case SUMMARIZING -> "summaries=" + artifacts.summaries.size();
            case INDEXING -> "snapshot=" + artifacts.snapshotId + " manifest=" + artifacts.manifestId;
            case VALIDATING -> "snapshot=" + artifacts.snapshotId;
            default -> "-";
        };
    }

    private List<GraphBuildState> sequenceFrom(GraphBuildState state) {
        if (state == GraphBuildState.QUEUED) {
            return FULL_SEQUENCE;
        }
        if (state == GraphBuildState.READY || state == GraphBuildState.PUBLISHED) {
            return null;
        }
        int index = FULL_SEQUENCE.indexOf(state);
        if (index < 0) {
            throw new IllegalStateException("图构建状态不可执行：" + state);
        }
        return FULL_SEQUENCE.subList(index, FULL_SEQUENCE.size());
    }

    private com.kwiki.graph.build.stage.GraphBuildStage stage(GraphBuildState state) {
        return switch (state) {
            case EXTRACTING -> extractionStage;
            case PROJECTING -> projectionStage;
            case CLUSTERING -> clusteringStage;
            case SUMMARIZING -> summarizationStage;
            case INDEXING -> indexingStage;
            case VALIDATING -> validationStage;
            default -> throw new IllegalStateException("状态没有对应阶段：" + state);
        };
    }

    private static GraphBuildStage stageOf(GraphBuildState state) {
        return switch (state) {
            case EXTRACTING -> GraphBuildStage.EXTRACTING;
            case PROJECTING -> GraphBuildStage.PROJECTING;
            case CLUSTERING -> GraphBuildStage.CLUSTERING;
            case SUMMARIZING -> GraphBuildStage.SUMMARIZING;
            case INDEXING -> GraphBuildStage.INDEXING;
            case VALIDATING -> GraphBuildStage.VALIDATING;
            default -> throw new IllegalStateException("状态没有对应阶段：" + state);
        };
    }

    private boolean sourceEpochsChanged(GraphBuildRunRecord run) {
        long[] epochs = sourceInventory.currentEpochs(run.kbId());
        return epochs == null || epochs.length < 2
                || epochs[0] != run.contentEpoch() || epochs[1] != run.securityEpoch();
    }

    private RunContext ctx(GraphBuildRunRecord run, String owner, long fencingToken,
                           BooleanSupplier leaseLost) {
        return new RunContext(run, owner, fencingToken,
                () -> leaseLost.getAsBoolean() || repository.isCancelled(run.id()),
                progress -> repository.updateProgress(run.id(), owner, fencingToken, progress),
                (timeout, willRetry) -> repository.recordModelAttemptFailure(run.id(), owner,
                        fencingToken, willRetry ? 1 : 0, timeout ? 1 : 0));
    }

    private static GraphBuildRunRecord withState(GraphBuildRunRecord run,
                                                 GraphBuildState state) {
        return new GraphBuildRunRecord(run.id(), run.batchId(), run.kbId(),
                run.chunkIndexVersion(), run.chunkPhysicalIndex(), run.communityIndexVersion(),
                run.communityPhysicalIndex(), run.graphVersion(), run.chunkIndexSchema(),
                run.entityLinkingVersion(), run.contentEpoch(), run.securityEpoch(), state,
                stageOf(state), run.fencingToken(), run.leaseOwner(), run.leaseExpiresAt(),
                run.eventWatermark(), run.extractor(), run.communityDetector(),
                run.summarizer());
    }
}
