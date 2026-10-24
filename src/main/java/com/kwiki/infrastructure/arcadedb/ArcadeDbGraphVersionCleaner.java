package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.config.ArcadeDbProperties;
import com.kwiki.graph.persistence.GraphVersionDataCleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 按 {@code (kbId, graphVersion)} 删除 ArcadeDB 业务库中的图数据。顶点删除时 ArcadeDB
 * 连带删除其边；每种顶点类型分批 DELETE ... LIMIT，并以 count 查询确认删净，避免单条
 * 大删除超时。库不存在视为已空。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class ArcadeDbGraphVersionCleaner implements GraphVersionDataCleaner {

    private static final Logger log = LoggerFactory.getLogger(ArcadeDbGraphVersionCleaner.class);

    /** 先删关系证据与实体，再删来源 Chunk 与社区顶点；类型名固定，不拼接外部输入。 */
    static final List<String> VERTEX_TYPES = List.of("RelationEvidence", "Entity", "Chunk", "Community");
    static final int DELETE_BATCH = 5_000;
    /** 单类型最多删除轮数：上限 = DELETE_BATCH × MAX_ROUNDS，超出视为异常而不是无限循环。 */
    static final int MAX_ROUNDS = 2_000;

    private final ArcadeDbHttpAdapter client;
    private final ArcadeDbProperties properties;
    private final ObjectMapper mapper;

    public ArcadeDbGraphVersionCleaner(ArcadeDbHttpAdapter client, ArcadeDbProperties properties,
                                       ObjectMapper mapper) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public void deleteGraphVersion(long kbId, long graphVersion) {
        String database = properties.database();
        if (!client.databaseExists(database)) {
            return;
        }
        Map<String, Object> params = Map.of("kbId", kbId, "graphVersion", graphVersion);
        for (String type : VERTEX_TYPES) {
            int rounds = 0;
            while (remaining(database, type, params) > 0) {
                if (++rounds > MAX_ROUNDS) {
                    throw new IllegalStateException("ArcadeDB " + type + " 删除未收敛：kb=" + kbId
                            + " graphVersion=" + graphVersion);
                }
                client.command(database, "DELETE FROM " + type
                                + " WHERE kbId = :kbId AND graphVersion = :graphVersion LIMIT " + DELETE_BATCH,
                        params, ArcadeDbTimeoutKind.BATCH_WRITE);
            }
        }
        log.info("ArcadeDB graph data deleted: kb={} graphVersion={}", kbId, graphVersion);
    }

    private long remaining(String database, String type, Map<String, Object> params) {
        ArcadeDbResponse response = client.query(database, "sql", "SELECT count(*) AS c FROM " + type
                + " WHERE kbId = :kbId AND graphVersion = :graphVersion", params);
        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode rows = root.isArray() ? root : root.path("result");
            return rows.isArray() && !rows.isEmpty() ? rows.get(0).path("c").asLong(0) : 0;
        } catch (Exception e) {
            throw new IllegalStateException("无法解析 ArcadeDB 计数结果", e);
        }
    }
}
