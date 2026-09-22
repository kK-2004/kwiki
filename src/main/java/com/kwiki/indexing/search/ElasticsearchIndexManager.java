package com.kwiki.indexing.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 别名背后的带版本索引生命周期：创建显式物理索引（幂等，同名冲突
 * mapping 拒绝）、按目标自身成功构建快照的维度校验映射、精确读取
 * 别名目标、原子切换与回滚。维度与 mapping 永远由调用方按版本快照
 * 传入，不使用进程级常量——切换前会再次核对别名恰好指向预期源索引。
 * 校验失败时当前可读索引保持活动。别名删除被限定在
 * kwiki-* 范围内，因为部署用的 API key 只授予该范围的读写权限。
 */
@Component
public class ElasticsearchIndexManager {

    public static final String ALIAS = "kwiki-chunks";
    public static final String PHYSICAL_NAME_PATTERN = "^kwiki-chunks-v([1-9][0-9]*)$";

    private final ElasticsearchClient client;
    private final ChunkMappingBuilder mappingBuilder;
    private final ObjectMapper mapper = new ObjectMapper();

    public ElasticsearchIndexManager(ObjectProvider<ElasticsearchClient> client,
                                     ChunkMappingBuilder mappingBuilder) {
        this.client = client.getIfAvailable();
        this.mappingBuilder = mappingBuilder;
    }

    public String indexNameFor(int version) {
        return "kwiki-chunks-v" + version;
    }

    public boolean available() {
        return client != null;
    }

    /** 初始引导只在别名不存在时执行，避免应用重启覆盖后续版本的活动别名。 */
    public boolean aliasExists() throws Exception {
        return client != null && client.indices()
                .existsAlias(request -> request.name(ALIAS))
                .value();
    }

    /** 精确列出别名当前指向的物理索引（对账与切换前源校验的事实来源）。 */
    public List<String> aliasTargets() throws Exception {
        if (client == null) {
            return List.of();
        }
        var response = client.indices().getAlias(request -> request.name(ALIAS));
        return List.copyOf(response.result().keySet());
    }

    /**
     * 幂等创建显式物理索引。索引已存在时按目标维度重新校验 mapping：
     * 同名但映射冲突（例如维度不同）直接拒绝，绝不在冲突索引上继续。
     */
    public String createVersionedIndex(String indexName, int embeddingDimensions) throws Exception {
        return createVersionedIndex(indexName, embeddingDimensions,
                ChunkMappingBuilder.MAPPING_SCHEMA_MULTIMODAL);
    }

    /** 按版本配置创建严格 mapping；实体代际必须使用新的物理索引。 */
    public String createVersionedIndex(String indexName, int embeddingDimensions,
                                       int mappingSchemaVersion) throws Exception {
        if (client == null) {
            return indexName;
        }
        Map<String, Object> body = mappingBuilder.buildMapping(embeddingDimensions,
                mappingSchemaVersion);
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("mapping serialization failed", e);
        }
        try {
            doCreateIndex(indexName, json);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException alreadyExists) {
            if (!"resource_already_exists_exception".equals(alreadyExists.error().type())) {
                throw alreadyExists;
            }
            String conflict = mappingSchemaVersion == ChunkMappingBuilder.MAPPING_SCHEMA_MULTIMODAL
                    ? validateIndex(indexName, embeddingDimensions)
                    : validateIndex(indexName, embeddingDimensions, mappingSchemaVersion);
            if (conflict != null) {
                throw new IllegalStateException(
                        "existing index " + indexName + " conflicts with the requested mapping: "
                                + conflict);
            }
        }
        return indexName;
    }

    /** 手动重建专用：只允许精确托管名称，且绝不删除当前别名目标。 */
    public void recreateOfflineVersion(String indexName, int embeddingDimensions) throws Exception {
        recreateOfflineVersion(indexName, embeddingDimensions,
                ChunkMappingBuilder.MAPPING_SCHEMA_MULTIMODAL);
    }

    /** 按版本配置重建离线物理索引。 */
    public void recreateOfflineVersion(String indexName, int embeddingDimensions,
                                       int mappingSchemaVersion) throws Exception {
        if (!indexName.matches(PHYSICAL_NAME_PATTERN)) {
            throw new IllegalArgumentException("refusing to recreate unmanaged index: " + indexName);
        }
        if (aliasTargets().contains(indexName)) {
            throw new IllegalStateException("cannot recreate the active alias target: " + indexName);
        }
        if (client != null && client.indices().exists(request -> request.index(indexName)).value()) {
            client.indices().delete(request -> request.index(indexName));
        }
        createVersionedIndex(indexName, embeddingDimensions, mappingSchemaVersion);
    }

    /** 删除一个精确托管物理索引；提交前再次以 ES 别名事实关闭竞态窗口。 */
    public boolean deletePhysicalIndex(String indexName) throws Exception {
        if (indexName == null || !indexName.matches(PHYSICAL_NAME_PATTERN)) {
            throw new IllegalArgumentException("refusing to delete unmanaged index: " + indexName);
        }
        if (aliasTargets().contains(indexName)) {
            throw new IllegalStateException("cannot delete an active alias target: " + indexName);
        }
        if (client == null) return true;
        if (!client.indices().exists(request -> request.index(indexName)).value()) return true;
        var response = client.indices().delete(request -> request.index(indexName));
        if (!response.acknowledged()) {
            throw new IllegalStateException("physical index deletion not acknowledged");
        }
        if (aliasTargets().contains(indexName)) {
            throw new IllegalStateException("deleted index unexpectedly remains an alias target");
        }
        return true;
    }

    /** 物理创建请求；拆出以便离线测试替换。 */
    void doCreateIndex(String indexName, String mappingJson) throws Exception {
        client.indices().create(CreateIndexRequest.of(request -> request
                .index(indexName)
                .withJson(new StringReader(mappingJson))));
    }

    /** 兼容时返回 null；否则返回拒绝切换别名的原因（v1 契约）。 */
    public String validateIndex(String indexName, int expectedDimensions) throws Exception {
        return validateIndex(indexName, expectedDimensions, 1);
    }

    /** 按 mapping schema 代校验：v2+ 额外要求 contentIds 资源字段。 */
    public String validateIndex(String indexName, int expectedDimensions,
                                int mappingSchemaVersion) throws Exception {
        if (client == null) {
            return null;
        }
        var response = client.indices().getMapping(request -> request.index(indexName));
        var record = response.result().get(indexName);
        if (record == null || record.mappings() == null) {
            return "missing mappings section";
        }
        return MappingValidator.validate(record.mappings(), expectedDimensions,
                mappingSchemaVersion);
    }

    /** 原子切换别名：先在所有位置移除该别名，再将其指向新索引。 */
    public void activateAlias(String indexName) throws Exception {
        if (client == null) {
            return;
        }
        client.indices().updateAliases(UpdateAliasesRequest.of(request -> request
                .actions(Action.of(action -> action.remove(
                        remove -> remove.index("kwiki-*").alias(ALIAS))))
                .actions(Action.of(action -> action.add(
                        add -> add.index(indexName).alias(ALIAS))))));
    }

    /** 精确源校验、单次原子 remove/add、ack 与唯一目标复核。 */
    public void atomicSwitchAlias(String expectedSource, String target) throws Exception {
        List<String> before = aliasTargets();
        if (!before.equals(List.of(expectedSource))) {
            throw new IllegalStateException("alias source mismatch");
        }
        if (client == null) return;
        var response = client.indices().updateAliases(UpdateAliasesRequest.of(request -> request
                .actions(Action.of(action -> action.remove(
                        remove -> remove.index(expectedSource).alias(ALIAS))))
                .actions(Action.of(action -> action.add(
                        add -> add.index(target).alias(ALIAS))))));
        if (!response.acknowledged()) throw new IllegalStateException("alias switch not acknowledged");
        if (!aliasTargets().equals(List.of(target))) {
            throw new IllegalStateException("alias target verification failed");
        }
    }

    /**
     * 为首次部署添加别名，不移除任何已有目标。多个实例同时启动时，
     * 对同一个索引重复添加相同别名仍然是幂等的。
     */
    public void createInitialAlias(String indexName) throws Exception {
        if (client == null) {
            return;
        }
        client.indices().updateAliases(UpdateAliasesRequest.of(request -> request
                .actions(Action.of(action -> action.add(
                        add -> add.index(indexName).alias(ALIAS))))));
    }

    public void rollbackAlias(String previousIndexName) throws Exception {
        if (client == null) {
            return;
        }
        client.indices().updateAliases(UpdateAliasesRequest.of(request -> request
                .actions(Action.of(action -> action.remove(
                        remove -> remove.index("kwiki-*").alias(ALIAS))))
                .actions(Action.of(action -> action.add(
                        add -> add.index(previousIndexName).alias(ALIAS))))));
    }

    /** 校验某个当前资源身份至少存在一个 chunk；不比较 parser 产生的 chunk 数。 */
    public boolean hasCurrentResource(String indexName, String resourceType, long resourceId,
                                      Long revisionId, long lifecycleVersion) throws Exception {
        if (client == null) return true;
        var response = client.count(request -> request.index(indexName).query(query ->
                query.bool(bool -> {
                    bool.filter(filter -> filter.term(term -> term.field("resourceType")
                            .value(resourceType)));
                    bool.filter(filter -> filter.term(term -> term.field("resourceId")
                            .value(resourceId)));
                    bool.filter(filter -> filter.term(term -> term.field("lifecycleVersion")
                            .value(lifecycleVersion)));
                    if (revisionId != null) {
                        bool.filter(filter -> filter.term(term -> term.field("revisionId")
                                .value(revisionId)));
                    }
                    return bool;
                })));
        return response.count() > 0;
    }

    /** 只读取验证所需元数据；明确排除 content，向量仅用于维度检查。 */
    public ValidationSnapshot validationSnapshot(String indexName, int limit) throws Exception {
        if (client == null) return new ValidationSnapshot(0, List.of(), false);
        List<String> fields = List.of("chunkLevel", "chunkKey", "parentChunkKey",
                "resourceType", "resourceId", "revisionId", "lifecycleVersion",
                "parserVersion", "chunkerVersion", "embeddingModel", "indexVersion", "vector");
        var response = client.search(request -> request.index(indexName).size(limit)
                .source(source -> source.filter(filter -> filter.includes(fields))), Map.class);
        List<Map<String, Object>> documents = response.hits().hits().stream()
                .map(hit -> hit.source()).filter(Objects::nonNull)
                .map(source -> mapper.convertValue(source,
                        new TypeReference<Map<String, Object>>() { }))
                .toList();
        long total = response.hits().total() == null
                ? documents.size() : response.hits().total().value();
        return new ValidationSnapshot(total, documents, total > documents.size());
    }

    public record ValidationSnapshot(long totalDocuments,
                                     List<Map<String, Object>> documents,
                                     boolean truncated) { }

    /** 不返回 _source 的有界词法/向量探针，只验证两条查询路径可执行。 */
    public void runValidationSmokeQueries(String indexName, int dimensions) throws Exception {
        if (client == null) return;
        client.search(request -> request.index(indexName).size(1).source(source -> source.fetch(false))
                .query(query -> query.match(match -> match.field("content")
                        .query("__kwiki_validation_probe__"))), Map.class);
        List<Float> zeroVector = java.util.Collections.nCopies(dimensions, 0.0f);
        client.search(request -> request.index(indexName).size(1).source(source -> source.fetch(false))
                .knn(knn -> knn.field("vector").queryVector(zeroVector).k(1).numCandidates(10)),
                Map.class);
    }
}
