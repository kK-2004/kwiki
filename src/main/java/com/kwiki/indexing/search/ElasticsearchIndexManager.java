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
 * Versioned index lifecycle behind an alias: create kwiki-chunks-v{n}, validate
 * mapping compatibility (dimensions + provenance fields), switch the alias
 * atomically, and roll back to the previous index on demand. The current readable
 * index stays active whenever validation fails. Alias removals are scoped to
 * kwiki-* because deployment API keys only grant read/write on that range.
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

    /** Creates the versioned index (idempotent) and returns its name. */
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

    /** Returns null when compatible; otherwise the reason alias switching is rejected. */
    public String validateIndex(String indexName) throws Exception {
        if (client == null) {
            return null;
        }
        var response = client.indices().getMapping(request -> request.index(indexName));
        Map<String, Object> mapping = mapper.convertValue(
                response.result().get(indexName), Map.class);
        return MappingValidator.validate(mapping, embeddingDimensions);
    }

    /** Atomic alias switch: removes the alias everywhere, then adds it to the new index. */
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
