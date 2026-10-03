package com.kwiki.graph.build.stage;

import com.kwiki.graph.CommunityIndexWriteRequest;
import com.kwiki.graph.CommunityMembership;
import com.kwiki.graph.CommunitySummary;
import com.kwiki.graph.GraphAlgorithmMode;
import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.build.RunArtifacts;
import com.kwiki.graph.build.RunContext;
import com.kwiki.graph.build.RetryPolicy;
import com.kwiki.graph.config.GraphBuildProperties;
import com.kwiki.graph.persistence.EntityMappingObservation;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.graph.persistence.GraphBuildRunRecord;
import com.kwiki.graph.persistence.GraphSnapshotIdentity;
import com.kwiki.graph.persistence.GraphSourceManifest;
import com.kwiki.graph.persistence.GraphSourceManifestBuilder;
import com.kwiki.graph.persistence.GraphSourceReadiness;
import com.kwiki.graph.plugin.CommunitySummarizerPlugin;
import com.kwiki.graph.plugin.CommunitySummarizerRegistry;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import com.kwiki.infrastructure.elasticsearch.CommunityIndexWriter;
import com.kwiki.infrastructure.elasticsearch.EntityMappingBackfillService;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.EntityLinkingStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * INDEXING 阶段：落库来源清单与 BUILDING 快照 → 分批写 COMMUNITY 物理索引 →
 * 回写 CHUNK 子块实体字段 → 更新快照计数。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class IndexingStage implements GraphBuildStage {

    /** 单次批写的社区数量；分批避免一次 bulk 过大。 */
    private static final int COMMUNITY_WRITE_BATCH = 200;

    private final GraphSourceManifestBuilder manifestBuilder;
    private final GraphBuildRepository repository;
    private final CommunityIndexWriter communityIndexWriter;
    private final EntityMappingBackfillService backfill;
    private final CommunitySummarizerRegistry summarizers;
    private final SearchIndexVersionRepository versions;
    private final GraphBuildProperties buildProperties;

    public IndexingStage(GraphSourceManifestBuilder manifestBuilder,
                         GraphBuildRepository repository,
                         CommunityIndexWriter communityIndexWriter,
                         EntityMappingBackfillService backfill,
                         CommunitySummarizerRegistry summarizers,
                         SearchIndexVersionRepository versions,
                         GraphBuildProperties buildProperties) {
        this.manifestBuilder = manifestBuilder;
        this.repository = repository;
        this.communityIndexWriter = communityIndexWriter;
        this.backfill = backfill;
        this.summarizers = summarizers;
        this.versions = versions;
        this.buildProperties = buildProperties;
    }

    @Override
    public void execute(RunContext ctx, RunArtifacts artifacts) {
        GraphBuildRunRecord run = ctx.run();
        CommunitySummarizerPlugin summarizer = summarizers.require(run.summarizer());
        RetryPolicy retry = RetryPolicy.of(buildProperties.maxAttempts(),
                buildProperties.retryBaseDelay());
        EditableIndexConfig versionConfig = versions.findByVersionNumber(run.chunkIndexVersion())
                .orElseThrow(() -> GraphBuildFailure.permanent("CHUNK_VERSION_GONE",
                        "Chunk 版本 v" + run.chunkIndexVersion() + " 不存在或已删除"))
                .editableConfig();

        persistManifestAndSnapshot(ctx, artifacts, summarizer, versionConfig);
        writeCommunities(ctx, artifacts, summarizer, versionConfig, retry);
        backfillChildren(ctx, artifacts, retry);
        repository.updateSnapshotCounts(artifacts.snapshotId, artifacts.entities.size(),
                artifacts.relations.size(), artifacts.summaries.size());
    }

    private void persistManifestAndSnapshot(RunContext ctx, RunArtifacts artifacts,
                                            CommunitySummarizerPlugin summarizer,
                                            EditableIndexConfig versionConfig) {
        var run = ctx.run();
        Map<String, GraphSourceReadiness> readiness = artifacts.sources.stream()
                .collect(Collectors.toMap(GraphSourceChunk::sourceChunkId,
                        ignored -> GraphSourceReadiness.READY, (a, b) -> a, LinkedHashMap::new));
        GraphSourceManifest manifest = manifestBuilder.build(run.id(), run.kbId(),
                run.chunkIndexVersion(), run.entityLinkingVersion(), run.contentEpoch(),
                run.securityEpoch(), run.eventWatermark(), true, artifacts.sources, readiness);
        artifacts.manifest = manifest;
        artifacts.manifestId = repository.persistManifest(manifest);
        artifacts.manifestHash = manifest.manifestHash();
        GraphSnapshotIdentity identity = new GraphSnapshotIdentity(
                run.extractor(), run.communityDetector(), run.summarizer(),
                GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED.name(),
                artifacts.detection.engineVersion(),
                com.kwiki.infrastructure.arcadedb.ArcadeDbSchemaDefinition.SCHEMA_VERSION,
                summarizer.modelName(), summarizer.promptVersion(),
                versionConfig.embeddingModel(), versionConfig.embeddingDimensions());
        artifacts.snapshotId = repository.createSnapshot(ctx.run(), artifacts.manifestId,
                artifacts.manifestHash, identity);
    }

    private void writeCommunities(RunContext ctx, RunArtifacts artifacts,
                                  CommunitySummarizerPlugin summarizer,
                                  EditableIndexConfig versionConfig,
                                  RetryPolicy retry) {
        if (artifacts.summaries.isEmpty()) {
            return;
        }
        var run = ctx.run();
        Map<String, List<CommunityMembership>> membersByCommunity =
                artifacts.detection.memberships().stream()
                        .collect(Collectors.groupingBy(CommunityMembership::communityId));
        List<CommunityIndexWriteRequest> requests = new ArrayList<>();
        for (Map.Entry<String, CommunitySummary> entry : artifacts.summaries.entrySet()) {
            String communityId = entry.getKey();
            List<String> memberIds = membersByCommunity
                    .getOrDefault(communityId, List.of()).stream()
                    .map(CommunityMembership::entityId).distinct().sorted().toList();
            requests.add(new CommunityIndexWriteRequest(run.communityPhysicalIndex(),
                    run.kbId(), run.graphVersion(), run.communityIndexVersion(), communityId,
                    String.valueOf(artifacts.manifestId), run.contentEpoch(),
                    run.securityEpoch(), run.chunkIndexVersion(), memberIds, memberIds.size(),
                    summarizer.modelName(), summarizer.promptVersion(),
                    versionConfig.embeddingModel(), versionConfig.embeddingDimensions(),
                    entry.getValue()));
        }
        for (int from = 0; from < requests.size(); from += COMMUNITY_WRITE_BATCH) {
            List<CommunityIndexWriteRequest> batch = requests.subList(from,
                    Math.min(from + COMMUNITY_WRITE_BATCH, requests.size()));
            CommunityIndexWriter.WriteResult result =
                    retry.run(() -> communityIndexWriter.write(batch));
            if (result == null || !result.verified()) {
                throw GraphBuildFailure.retryable("COMMUNITY_INDEX_VERIFY_FAILED",
                        "社区索引写入后读回校验未通过");
            }
        }
        artifacts.communityIndexVerified = true;
    }

    private void backfillChildren(RunContext ctx, RunArtifacts artifacts, RetryPolicy retry) {
        var run = ctx.run();
        Map<String, EntityMappingObservation> observations = new HashMap<>();
        for (GraphSourceChunk source : artifacts.sources) {
            ctx.throwIfCancelled();
            ChunkHit hit = artifacts.childrenBySourceChunkId.get(source.sourceChunkId());
            if (hit == null) {
                throw GraphBuildFailure.permanent("SOURCE_CHUNK_MISSING",
                        "子块在物理索引中缺失：" + source.chunkKey());
            }
            List<String> entityIds = artifacts.extractedEntityIds
                    .getOrDefault(source.sourceChunkId(), List.of());
            EntityMappingBackfillService.Outcome outcome = retry.run(() ->
                    backfill.backfill(new EntityMappingBackfillService.Request(
                            run.chunkPhysicalIndex(), hit.chunkKey(), source,
                            run.entityLinkingVersion(), entityIds)));
            switch (outcome) {
                case UPDATED -> observations.put(source.sourceChunkId(),
                        new EntityMappingObservation(source.sourceChunkId(), entityIds,
                                run.entityLinkingVersion(), EntityLinkingStatus.READY));
                case SOURCE_MISMATCH, NOT_FOUND -> throw GraphBuildFailure.permanent(
                        "ENTITY_BACKFLOW_MISMATCH",
                        "子块实体回填身份不匹配：" + hit.chunkKey());
                case UNAVAILABLE -> throw GraphBuildFailure.retryable(
                        "ELASTICSEARCH_UNAVAILABLE", "Elasticsearch 未配置");
            }
        }
        artifacts.observations = observations;
    }
}
