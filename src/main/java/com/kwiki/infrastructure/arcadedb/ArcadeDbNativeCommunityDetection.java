package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.CommunityDetectionInput;
import com.kwiki.graph.CommunityDetectionPort;
import com.kwiki.graph.CommunityDetectionResult;
import com.kwiki.graph.CommunityMembership;
import com.kwiki.graph.GraphAlgorithmMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 只调用 ArcadeDB 内置无权 Leiden，不在应用内实现或替换聚类算法。 */
@Component
@ConditionalOnBean(ArcadeDbHttpAdapter.class)
public class ArcadeDbNativeCommunityDetection implements CommunityDetectionPort {

    private final ArcadeDbHttpAdapter adapter;
    private final com.kwiki.graph.config.ArcadeDbProperties properties;
    private final ObjectMapper mapper;

    public ArcadeDbNativeCommunityDetection(ArcadeDbHttpAdapter adapter,
                                            com.kwiki.graph.config.ArcadeDbProperties properties,
                                            ObjectMapper mapper) {
        this.adapter = adapter;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public CommunityDetectionResult detect(CommunityDetectionInput input) {
        if (input.algorithmMode() != GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED) {
            throw new IllegalArgumentException("只支持 ArcadeDB 原生无权 Leiden");
        }
        String statement = "SELECT entityId, communityId FROM "
                + "algo.leiden('CONNECTED', :maxIterations, :resolution)";
        var response = adapter.command(properties.database(), statement,
                Map.of("maxIterations", input.maxIterations(), "resolution", input.resolution()),
                ArcadeDbTimeoutKind.ALGORITHM);
        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode rows = root.isArray() ? root : root.path("result");
            List<CommunityMembership> memberships = new ArrayList<>();
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    String entityId = text(row, "entityId", "entity_id");
                    String communityId = text(row, "communityId", "community_id");
                    if (entityId != null && communityId != null) {
                        memberships.add(new CommunityMembership(input.kbId(), input.graphVersion(),
                                entityId, communityId));
                    }
                }
            }
            return new CommunityDetectionResult(input.algorithmMode(),
                    root.path("engineVersion").asText("unknown"), memberships);
        } catch (Exception failure) {
            throw new IllegalStateException("ArcadeDB Leiden 输出无法解析", failure);
        }
    }

    private static String text(JsonNode row, String first, String second) {
        JsonNode value = row.has(first) ? row.get(first) : row.get(second);
        return value == null || value.isNull() ? null : value.asText();
    }
}
