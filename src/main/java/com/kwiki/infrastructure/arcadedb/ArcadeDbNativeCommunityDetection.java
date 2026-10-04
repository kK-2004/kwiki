package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.CommunityDetectionInput;
import com.kwiki.graph.CommunityDetectionPort;
import com.kwiki.graph.CommunityDetectionResult;
import com.kwiki.graph.CommunityMembership;
import com.kwiki.graph.GraphAlgorithmMode;
import com.kwiki.graph.UnweightedGraphProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 只调用 ArcadeDB 内置无权 Leiden（Cypher 过程 algo.leiden，26.3.1+），不在应用内实现聚类。
 * 在专用计算库中为每个 run 建独立的顶点/边类型，只灌入本次投影的实体与无向边，
 * 按本 run 的边类型调用 algo.leiden，跑完删除类型。实测（26.9.1）该过程会返回库内
 * 所有顶点（含并行 run 的顶点，它们没有本 run 的边，作为孤点不影响模块度），
 * 因此只保留 RID 属于本 run 顶点类型的结果。
 */
@Component
// 组件扫描阶段 @ConditionalOnBean 看不到配置类中的 @Bean，改用与 ArcadeDbHttpAdapter 相同的开关条件
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class ArcadeDbNativeCommunityDetection implements CommunityDetectionPort {

    private static final Logger log = LoggerFactory.getLogger(ArcadeDbNativeCommunityDetection.class);
    static final String ENGINE_VERSION = "arcadedb-algo.leiden";
    static final int WRITE_BATCH = 500;
    static final String INSERT_ENTITIES = "UNWIND $entityIds AS entityId CREATE (:%s {entityId: entityId})";
    static final String INSERT_EDGES = "UNWIND $edges AS edge "
            + "MATCH (a:%1$s {entityId: edge.left}), (b:%1$s {entityId: edge.right}) "
            + "CREATE (a)-[:%2$s]->(b)";
    static final String ENTITY_RIDS = "SELECT @rid AS rid, entityId FROM %s";

    private final ArcadeDbHttpAdapter adapter;
    private final ArcadeDbScratchProjectionSpace scratch;
    private final ObjectMapper mapper;

    public ArcadeDbNativeCommunityDetection(ArcadeDbHttpAdapter adapter,
                                            ArcadeDbScratchProjectionSpace scratch,
                                            ObjectMapper mapper) {
        this.adapter = adapter;
        this.scratch = scratch;
        this.mapper = mapper;
    }

    @Override
    public CommunityDetectionResult detect(CommunityDetectionInput input) {
        if (input.algorithmMode() != GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED) {
            throw new IllegalArgumentException("只支持 ArcadeDB 原生无权 Leiden");
        }
        UnweightedGraphProjection projection = input.projection();
        if (projection.entityIds().isEmpty()) {
            return new CommunityDetectionResult(input.algorithmMode(), ENGINE_VERSION, List.of());
        }
        var lease = scratch.create(runId(input));
        boolean finished = false;
        try {
            load(lease, projection);
            JsonNode communities = rows(adapter.command(lease.database(), "cypher",
                    leidenStatement(input, lease.edgeType()), Map.of(), ArcadeDbTimeoutKind.ALGORITHM));
            Map<String, String> entityByRid = entityByRid(lease);
            finished = true;
            return new CommunityDetectionResult(input.algorithmMode(), ENGINE_VERSION,
                    memberships(input, communities, entityByRid));
        } finally {
            cleanup(lease, finished);
        }
    }

    /** 参数来自配置的整数/浮点数，按字面量内联，避免依赖过程参数绑定的行为差异。 */
    static String leidenStatement(CommunityDetectionInput input, String edgeType) {
        return String.format(Locale.ROOT,
                "CALL algo.leiden('%s', %d, %s) YIELD nodeId, community "
                        + "RETURN nodeId, community",
                edgeType, input.maxIterations(), Double.toString(input.resolution()));
    }

    private void load(ArcadeDbScratchProjectionSpace.Lease lease, UnweightedGraphProjection projection) {
        String database = lease.database();
        String insertEntities = String.format(Locale.ROOT, INSERT_ENTITIES, lease.vertexType());
        String insertEdges = String.format(Locale.ROOT, INSERT_EDGES, lease.vertexType(), lease.edgeType());
        List<String> entityIds = projection.entityIds();
        for (int from = 0; from < entityIds.size(); from += WRITE_BATCH) {
            List<String> batch = entityIds.subList(from, Math.min(entityIds.size(), from + WRITE_BATCH));
            adapter.command(database, "cypher", insertEntities,
                    Map.of("entityIds", new ArrayList<>(batch)), ArcadeDbTimeoutKind.BATCH_WRITE);
        }
        List<UnweightedGraphProjection.Edge> edges = projection.edges();
        for (int from = 0; from < edges.size(); from += WRITE_BATCH) {
            List<Map<String, Object>> batch = new ArrayList<>();
            for (var edge : edges.subList(from, Math.min(edges.size(), from + WRITE_BATCH))) {
                batch.add(Map.of("left", edge.leftEntityId(), "right", edge.rightEntityId()));
            }
            adapter.command(database, "cypher", insertEdges, Map.of("edges", batch),
                    ArcadeDbTimeoutKind.BATCH_WRITE);
        }
    }

    private Map<String, String> entityByRid(ArcadeDbScratchProjectionSpace.Lease lease) {
        Map<String, String> entityByRid = new HashMap<>();
        for (JsonNode row : rows(adapter.query(lease.database(),
                String.format(Locale.ROOT, ENTITY_RIDS, lease.vertexType()), Map.of()))) {
            String rid = rid(row.get("rid"));
            JsonNode entityId = row.get("entityId");
            if (rid != null && entityId != null && !entityId.isNull()) {
                entityByRid.put(rid, entityId.asText());
            }
        }
        return entityByRid;
    }

    private static List<CommunityMembership> memberships(CommunityDetectionInput input, JsonNode rows,
                                                         Map<String, String> entityByRid) {
        List<CommunityMembership> memberships = new ArrayList<>();
        for (JsonNode row : rows) {
            String entityId = entityByRid.get(rid(row.get("nodeId")));
            JsonNode community = row.get("community");
            if (entityId == null || community == null || community.isNull()) {
                continue;
            }
            memberships.add(new CommunityMembership(input.kbId(), input.graphVersion(),
                    entityId, "c" + community.asText()));
        }
        if (memberships.isEmpty()) {
            throw new IllegalStateException("ArcadeDB Leiden 未返回可识别的社区成员");
        }
        return memberships;
    }

    /** RID 可能序列化为 "#1:0" 字符串，也可能是带 @rid 的顶点对象。 */
    private static String rid(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            JsonNode inner = node.has("@rid") ? node.get("@rid") : node.get("rid");
            return inner == null || inner.isNull() ? null : inner.asText();
        }
        return node.asText();
    }

    private JsonNode rows(ArcadeDbResponse response) {
        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode rows = root.isArray() ? root : root.path("result");
            return rows.isArray() ? rows : mapper.createArrayNode();
        } catch (Exception failure) {
            throw new IllegalStateException("ArcadeDB Leiden 输出无法解析", failure);
        }
    }

    private void cleanup(ArcadeDbScratchProjectionSpace.Lease lease, boolean finished) {
        try {
            scratch.drop(lease);
        } catch (RuntimeException e) {
            // 算法超时等情况下远端可能仍在运行，删类型失败只告警；同一 run 重试时会先清理残留
            log.warn("ArcadeDB 临时投影类型清理失败 database={} vertexType={} finished={} error={}",
                    lease.database(), lease.vertexType(), finished, e.getMessage());
        }
    }

    private static long runId(CommunityDetectionInput input) {
        try {
            return Long.parseLong(input.runId());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("社区检测 runId 必须是数字: " + input.runId(), e);
        }
    }
}
