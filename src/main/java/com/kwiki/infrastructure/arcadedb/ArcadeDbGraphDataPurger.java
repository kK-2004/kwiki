package com.kwiki.infrastructure.arcadedb;

import com.kwiki.graph.GraphDataPurger;
import com.kwiki.graph.config.ArcadeDbProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ArcadeDB 图数据清空：先截断边类型再截断顶点类型（UNSAFE 允许截断带边的类型），
 * 保留 SchemaVersion 与类型定义。库不存在时视为已空。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class ArcadeDbGraphDataPurger implements GraphDataPurger {

    private static final Logger log = LoggerFactory.getLogger(ArcadeDbGraphDataPurger.class);

    static final List<String> EDGE_TYPES =
            List.of("CONTAINS", "MENTIONS", "RELATES_TO", "SUPPORTS", "SOURCE_CHUNK", "IN_COMMUNITY");
    static final List<String> VERTEX_TYPES =
            List.of("Community", "RelationEvidence", "Entity", "Chunk", "Document");

    private final ArcadeDbHttpAdapter client;
    private final ArcadeDbProperties properties;

    public ArcadeDbGraphDataPurger(ArcadeDbHttpAdapter client, ArcadeDbProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public List<String> purgeAll() {
        if (!client.databaseExists(properties.database())) {
            return List.of();
        }
        List<String> purged = new ArrayList<>();
        for (String type : concat(EDGE_TYPES, VERTEX_TYPES)) {
            client.command(properties.database(), "TRUNCATE TYPE " + type + " UNSAFE", Map.of(),
                    ArcadeDbTimeoutKind.BATCH_WRITE);
            purged.add(type);
        }
        log.warn("ArcadeDB graph data purged for force reset: database={}, types={}", properties.database(), purged);
        return purged;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
