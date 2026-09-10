package com.kwiki.indexing.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.Map;

/**
 * 别名背后的带版本索引生命周期：创建 kwiki-chunks-v{n}、校验
 * 映射兼容性（维度 + 溯源字段）、原子地切换
 * 别名，并按需回滚到上一个索引。校验失败时
 * 当前可读索引保持活动。别名删除被限定在
 * kwiki-* 范围内，因为部署用的 API key 只授予该范围的读写权限。
 */
@Component
public class ElasticsearchIndexManager {

    public static final String ALIAS = "kwiki-chunks";

    private final ElasticsearchClient client;
    private final ChunkMappingBuilder mappingBuilder;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int embeddingDimensions;

    public ElasticsearchIndexManager(ObjectProvider<ElasticsearchClient> client,
                                     ChunkMappingBuilder mappingBuilder,
                                     @Value("${kwiki.indexing.embedding-dimensions:1024}") int embeddingDimensions) {
        this.client = client.getIfAvailable();
        this.mappingBuilder = mappingBuilder;
        this.embeddingDimensions = embeddingDimensions;
    }

    public String indexNameFor(int version) {
        return "kwiki-chunks-v" + version;
    }

    /** 创建带版本号的索引（幂等）并返回其名称。 */
    public String createVersionedIndex(int version) throws Exception {
        if (client == null) {
            return indexNameFor(version);
        }
        String indexName = indexNameFor(version);
        Map<String, Object> body = mappingBuilder.buildMapping(embeddingDimensions);
        try {
            String json = mapper.writeValueAsString(body);
            client.indices().create(CreateIndexRequest.of(request -> request
                    .index(indexName)
                    .withJson(new StringReader(json))));
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException alreadyExists) {
            if (!"resource_already_exists_exception".equals(alreadyExists.error().type())) {
                throw alreadyExists;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("mapping serialization failed", e);
        }
        return indexName;
    }

    /** 兼容时返回 null；否则返回拒绝切换别名的原因。 */
    public String validateIndex(String indexName) throws Exception {
        if (client == null) {
            return null;
        }
        var response = client.indices().getMapping(request -> request.index(indexName));
        Map<String, Object> mapping = mapper.convertValue(
                response.result().get(indexName), Map.class);
        return MappingValidator.validate(mapping, embeddingDimensions);
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
}
