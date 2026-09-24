package com.kwiki.graph.persistence;

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
 */
@Service
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphBuildBatchService {

    private final GraphBuildRepository repository;
    private final GraphSourceEpochService epochs;

    public GraphBuildBatchService(GraphBuildRepository repository) {
        this(repository, null);
    }

    @Autowired
    public GraphBuildBatchService(GraphBuildRepository repository,
                                  GraphSourceEpochService epochs) {
        this.repository = repository;
        this.epochs = epochs;
    }

    @Transactional
    public GraphBuildSubmission submit(GraphBuildBatchRequest request) {
        var existing = repository.findBatchByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return new GraphBuildSubmission(existing.get(), 0, List.of(), true);
        }
        if (request.mappingSchemaVersion() < 3) {
            throw new IllegalArgumentException("图构建目标必须支持实体映射 schema v3");
        }
        long communityVersion = repository.allocateCommunityIndexVersion(
                "kwiki-communities-v%d-kb%%d", request.mappingSchemaVersion(),
                request.configRevision());
        GraphBuildBatchCommand batch = new GraphBuildBatchCommand(
                request.idempotencyKey(), request.scopeKind(), request.knowledgeBaseIdsJson(),
                request.chunkIndexVersion(), request.chunkPhysicalIndex(), communityVersion,
                request.autoPublish(), request.requestedBy(), request.scheduleDate());
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
                    request.mappingSchemaVersion(), request.entityLinkingVersion(),
                    currentEpochs[0], currentEpochs[1])));
        }
        return new GraphBuildSubmission(batchId, communityVersion, runIds, false);
    }
}
