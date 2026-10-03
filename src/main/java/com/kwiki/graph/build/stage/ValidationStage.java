package com.kwiki.graph.build.stage;

import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.build.RunArtifacts;
import com.kwiki.graph.build.RunContext;
import com.kwiki.graph.persistence.GraphSnapshotAutoPublishService;
import com.kwiki.graph.persistence.GraphSnapshotSealingService;
import com.kwiki.graph.persistence.GraphSnapshotState;
import com.kwiki.graph.persistence.GraphSnapshotValidationReport;
import com.kwiki.graph.persistence.GraphSnapshotValidationService;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.graph.plugin.CommunitySummarizerPlugin;
import com.kwiki.graph.plugin.CommunitySummarizerRegistry;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * VALIDATING 阶段：组装统一校验输入 → 生成报告 → 封存 READY →
 * 批次开启 autoPublish 时交给自动发布门禁。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class ValidationStage implements GraphBuildStage {

    private final GraphSnapshotValidationService validation;
    private final GraphSnapshotSealingService sealing;
    private final GraphSnapshotAutoPublishService autoPublish;
    private final GraphBuildRepository repository;
    private final CommunitySummarizerRegistry summarizers;
    private final SearchIndexVersionRepository versions;

    public ValidationStage(GraphSnapshotValidationService validation,
                           GraphSnapshotSealingService sealing,
                           GraphSnapshotAutoPublishService autoPublish,
                           GraphBuildRepository repository,
                           CommunitySummarizerRegistry summarizers,
                           SearchIndexVersionRepository versions) {
        this.validation = validation;
        this.sealing = sealing;
        this.autoPublish = autoPublish;
        this.repository = repository;
        this.summarizers = summarizers;
        this.versions = versions;
    }

    @Override
    public void execute(RunContext ctx, RunArtifacts artifacts) {
        var run = ctx.run();
        CommunitySummarizerPlugin summarizer = summarizers.require(run.summarizer());
        var versionConfig = versions.findByVersionNumber(run.chunkIndexVersion())
                .orElseThrow(() -> GraphBuildFailure.permanent("CHUNK_VERSION_GONE",
                        "Chunk 版本 v" + run.chunkIndexVersion() + " 不存在或已删除"))
                .editableConfig();

        Map<String, Integer> observedEmbeddingDimensions = new HashMap<>();
        artifacts.summaries.keySet().forEach(communityId ->
                observedEmbeddingDimensions.put(communityId, versionConfig.embeddingDimensions()));
        GraphSnapshotValidationService.ConfigIdentity expected =
                new GraphSnapshotValidationService.ConfigIdentity(run.chunkIndexSchema(),
                        run.entityLinkingVersion(), summarizer.modelName(),
                        summarizer.promptVersion(), versionConfig.embeddingModel(),
                        versionConfig.embeddingDimensions());
        GraphSnapshotValidationService.ConfigIdentity observed = expected;

        GraphSnapshotValidationReport report = validation.validate(
                new GraphSnapshotValidationService.Input(run.kbId(), run.graphVersion(),
                        artifacts.manifest, List.copyOf(artifacts.observations.values()),
                        artifacts.extractedEntityIds, artifacts.extractedEntityIds,
                        artifacts.projection.entityIds(), artifacts.detection.memberships(),
                        artifacts.summaries, observedEmbeddingDimensions, expected, observed,
                        artifacts.communityIndexVerified));

        boolean sealed = sealing.seal(artifacts.snapshotId, report);
        if (!sealed) {
            throw GraphBuildFailure.permanent("SNAPSHOT_VALIDATION_FAILED",
                    "快照校验未通过：" + report.failures().stream()
                            .map(check -> check.name() + "(" + check.detail() + ")")
                            .collect(Collectors.joining("、")));
        }
        var snapshot = repository.findSnapshotById(artifacts.snapshotId)
                .orElseThrow(() -> GraphBuildFailure.permanent("SNAPSHOT_MISSING",
                        "快照封存后无法读回：" + artifacts.snapshotId))
                .snapshot();
        autoPublish.publishIfAutoPublishEnabled(run.id(), snapshot, GraphSnapshotState.READY,
                report.toChecklist(), null);
    }
}
