package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.json.JsonData;
import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.indexing.search.ChunkIndexRepository;
import com.kwiki.rag.retrieval.EntityLinkingStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只更新指定物理 Chunk 索引中的实体字段。来源身份在 ES 脚本内再次
 * 比对，避免延迟目标把旧修订的映射写到新正文；脚本不使用 upsert，
 * 因而已删除 CHILD 不会被回填任务重新创建。
 */
@Component
public class EntityMappingBackfillService {

    private static final String SOURCE_GUARD_SCRIPT = """
            boolean same(String actual, String expected) {
              return String.valueOf(actual) == expected;
            }
            if (!same(ctx._source.sourceChunkId, params.sourceChunkId)
                || !same(ctx._source.kbId, params.kbId)
                || !same(ctx._source.resourceType, params.resourceType)
                || !same(ctx._source.resourceId, params.resourceId)
                || !same(ctx._source.revisionId, params.revisionId)
                || !same(ctx._source.lifecycleVersion, params.lifecycleVersion)
                || !same(ctx._source.indexVersion, params.indexVersion)
                || !same(ctx._source.parserVersion, params.parserVersion)
                || !same(ctx._source.chunkerVersion, params.chunkerVersion)
                || !same(ctx._source.entityLinkingVersion, params.entityLinkingVersion)) {
              ctx.op = 'none';
              return;
            }
            ctx._source.entityIds = params.entityIds;
            ctx._source.entityLinkingVersion = params.entityLinkingVersion;
            ctx._source.entityLinkingStatus = 'READY';
            """;

    private final ElasticsearchClient client;

    public EntityMappingBackfillService(ObjectProvider<ElasticsearchClient> client) {
        this.client = client.getIfAvailable();
    }

    public enum Outcome {
        UPDATED,
        SOURCE_MISMATCH,
        NOT_FOUND,
        UNAVAILABLE
    }

    public record Request(String physicalIndex, String childChunkKey,
                          GraphSourceChunk sourceChunk, String entityLinkingVersion,
                          List<String> entityIds) {
        public Request {
            ChunkIndexRepository.requirePhysicalIndex(physicalIndex);
            if (childChunkKey == null || childChunkKey.isBlank()) {
                throw new IllegalArgumentException("child chunk key is required");
            }
            if (sourceChunk == null || entityLinkingVersion == null
                    || entityLinkingVersion.isBlank()) {
                throw new IllegalArgumentException("entity mapping identity is required");
            }
            entityIds = entityIds == null ? List.of() : entityIds.stream()
                    .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        }
    }

    public Outcome backfill(Request request) {
        if (client == null) {
            return Outcome.UNAVAILABLE;
        }
        GraphSourceChunk source = request.sourceChunk();
        Map<String, JsonData> params = new LinkedHashMap<>();
        params.put("sourceChunkId", JsonData.of(source.sourceChunkId()));
        params.put("kbId", JsonData.of(Long.toString(source.kbId())));
        params.put("resourceType", JsonData.of(source.resourceType()));
        params.put("resourceId", JsonData.of(Long.toString(source.resourceId())));
        params.put("revisionId", JsonData.of(String.valueOf(source.revisionId())));
        params.put("lifecycleVersion", JsonData.of(Long.toString(source.lifecycleVersion())));
        params.put("indexVersion", JsonData.of(Long.toString(source.indexVersion())));
        params.put("parserVersion", JsonData.of(source.parserVersion()));
        params.put("chunkerVersion", JsonData.of(source.chunkerVersion()));
        params.put("entityLinkingVersion", JsonData.of(request.entityLinkingVersion()));
        params.put("entityIds", JsonData.of(request.entityIds()));

        try {
            UpdateRequest<Map<String, Object>, Map<String, Object>> update =
                    new UpdateRequest.Builder<Map<String, Object>, Map<String, Object>>()
                            .index(request.physicalIndex())
                            .id(request.childChunkKey())
                            .script(script -> script.lang("painless")
                                    .source(SOURCE_GUARD_SCRIPT).params(params))
                            .docAsUpsert(false)
                            .build();
            var response = client.update(update, Map.class);
            return response.result() == Result.Updated
                    ? Outcome.UPDATED : Outcome.SOURCE_MISMATCH;
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException failure) {
            return failure.status() == 404 ? Outcome.NOT_FOUND : throwFailure(failure);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("entity mapping backfill failed", failure);
        }
    }

    private static Outcome throwFailure(
            co.elastic.clients.elasticsearch._types.ElasticsearchException failure) {
        throw new IllegalStateException("entity mapping backfill rejected", failure);
    }
}
