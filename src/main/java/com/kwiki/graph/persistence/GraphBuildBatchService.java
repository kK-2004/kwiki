package com.kwiki.graph.persistence;

import com.kwiki.indexing.schema.ChunkIndexSchemaRegistry;
import com.kwiki.indexing.schema.ChunkIndexSchema;
import com.kwiki.indexing.plugin.IndexCapability;
import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry;
import com.kwiki.indexing.version.SearchIndexVersionRepository;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 图构建提交边界：在创建子任务前固定来源范围、CHUNK/COMMUNITY 双版本
 * 和每库 graphVersion。调度器与管理 API 都应复用此入口，避免执行时
 * 再解析别名或把多个知识库伪装成一个社区物理索引。
 * 三个可替换环节的插件在提交时确定并写入批次与子任务。
 */
@Service
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphBuildBatchService {

    private final GraphBuildRepository repository;
    private final GraphSourceEpochService epochs;
    private final com.kwiki.graph.config.GraphPluginDefaults pluginDefaults;
    private final com.kwiki.graph.plugin.GraphExtractorRegistry extractors;
    private final com.kwiki.graph.plugin.CommunityDetectorRegistry detectors;
    private final com.kwiki.graph.plugin.CommunitySummarizerRegistry summarizers;

    private final VersionedIndexingPipelineRegistry pipelines;
    private final SearchIndexVersionRepository versions;
    private final ChunkIndexSchemaRegistry schemas;

    @Autowired
    public GraphBuildBatchService(GraphBuildRepository repository, GraphSourceEpochService epochs,
                                  VersionedIndexingPipelineRegistry pipelines,
                                  SearchIndexVersionRepository versions,
                                  ChunkIndexSchemaRegistry schemas,
                                  com.kwiki.graph.config.GraphPluginDefaults pluginDefaults,
                                  com.kwiki.graph.plugin.GraphExtractorRegistry extractors,
                                  com.kwiki.graph.plugin.CommunityDetectorRegistry detectors,
                                  com.kwiki.graph.plugin.CommunitySummarizerRegistry summarizers) {
        this.repository = repository;
        this.epochs = epochs;
        this.pipelines = pipelines;
        this.versions = versions;
        this.schemas = schemas;
        this.pluginDefaults = pluginDefaults;
        this.extractors = extractors;
        this.detectors = detectors;
        this.summarizers = summarizers;
    }

    @Transactional
    public GraphBuildSubmission submit(GraphBuildBatchRequest request) {
        var existing = repository.findBatchByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return new GraphBuildSubmission(existing.get(), 0, List.of(), true);
        }
        var target = versions.findByVersionNumber(request.chunkIndexVersion())
                .filter(version -> version.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("Chunk 版本不存在或已删除"));
        var config = target.editableConfig();
        if (!pipelines.supportsCapability(config, IndexCapability.ENTITY_LINKING)) {
            String label = schemas.find(config.indexSchema()).map(ChunkIndexSchema::label)
                    .orElse(config.indexSchema());
            throw new IllegalArgumentException("所选 Chunk 版本的字段格式 " + label + "（" + config.indexSchema()
                    + "）不支持实体关联，无法用于图构建");
        }
        if (!request.chunkIndexSchema().equals(config.indexSchema())
                || request.configRevision() != target.getConfigRevision()
                || !request.chunkPhysicalIndex().equals(target.getPhysicalName())) {
            throw new IllegalArgumentException("图构建目标配置已变化，请重新提交");
        }
        // 提交时确定三项插件：缺省取默认插件；未注册或不可用直接拒绝
        String extractor = requireUsable(extractors, request.extractor(),
                pluginDefaults.extractor());
        String communityDetector = requireUsable(detectors, request.communityDetector(),
                pluginDefaults.communityDetector());
        String summarizer = requireUsable(summarizers, request.summarizer(),
                pluginDefaults.summarizer());
        // COMMUNITY 映射独立使用自身的整数结构版本，不能沿用 CHUNK 字段格式。
        long communityVersion = repository.allocateCommunityIndexVersion(
                "kwiki-communities-v%d-kb%%d", 3, request.configRevision());
        GraphBuildBatchCommand batch = new GraphBuildBatchCommand(
                request.idempotencyKey(), request.scopeKind(), request.knowledgeBaseIdsJson(),
                request.chunkIndexVersion(), request.chunkPhysicalIndex(), communityVersion,
                request.autoPublish(), request.requestedBy(), request.scheduleDate(),
                extractor, communityDetector, summarizer);
        long batchId = repository.createBatch(batch);
        repository.bindCommunityVersionToBatch(communityVersion, batchId);

        List<Long> runIds = new ArrayList<>();
        for (long kbId : request.knowledgeBaseIds()) {
            long graphVersion = repository.allocateGraphVersion(kbId);
            String communityIndex = "kwiki-communities-v" + communityVersion + "-kb" + kbId;
            long[] currentEpochs = epochs == null ? new long[]{0, 0} : epochs.current(kbId);
            runIds.add(repository.createRun(new GraphBuildRunCommand(
                    batchId, kbId, request.chunkIndexVersion(), request.chunkPhysicalIndex(),
                    communityVersion, communityIndex, graphVersion,
                    request.chunkIndexSchema(), request.entityLinkingVersion(),
                    currentEpochs[0], currentEpochs[1],
                    batch.extractor(), batch.communityDetector(), batch.summarizer())));
        }
        return new GraphBuildSubmission(batchId, communityVersion, runIds, false);
    }

    /** 解析插件选择：缺省取默认值；未注册或不可用拒绝并说明原因。 */
    private <T extends com.kwiki.indexing.plugin.IndexPlugin> String requireUsable(
            com.kwiki.indexing.plugin.PluginRegistry<T> registry, String requested, String fallback) {
        String id = requested == null || requested.isBlank() ? fallback : requested;
        T plugin = registry.find(id).orElseThrow(() -> new IllegalArgumentException(
                registry.kind() + " " + id + " 不可用：未注册"));
        var availability = plugin.availability();
        if (!availability.available()) {
            throw new IllegalArgumentException(registry.kind() + " " + id + " 不可用："
                    + availability.unavailableReason());
        }
        return id;
    }
}
