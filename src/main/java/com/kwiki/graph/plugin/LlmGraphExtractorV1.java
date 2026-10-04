package com.kwiki.graph.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphEntityType;
import com.kwiki.graph.GraphExtractionInput;
import com.kwiki.graph.GraphExtractionPrompt;
import com.kwiki.graph.GraphExtractionResult;
import com.kwiki.graph.GraphPredicate;
import com.kwiki.graph.GraphRelation;
import com.kwiki.graph.GraphSourceRef;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.model.GraphModelClients;
import com.kwiki.graph.model.ModelEndpoint;
import com.kwiki.indexing.plugin.PluginAvailability;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 首个大模型抽取插件：组装版本化提示词，解析 JSON 输出并回填稳定关系身份。 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class LlmGraphExtractorV1 implements GraphExtractorPlugin {

    public static final String ID = "kwiki-extractor-1";

    private static final String FORMAT_INSTRUCTIONS = """

            输出格式：只输出一个 JSON 对象，不要输出 JSON 以外的任何文字。
            {"entities":[{"id":"e1","name":"实体名","type":"PERSON|ORGANIZATION|PRODUCT|TECHNOLOGY|CONCEPT|LOCATION|EVENT|OTHER","aliases":["别名"]}],
             "relations":[{"source":"e1","target":"e2","predicate":"USES|DEPENDS_ON|PART_OF|IMPLEMENTS|BASED_ON|RELATED_TO","confidence":0.8,"start":12,"end":40}]}
            id 是本次输出内自定义的实体编号；start/end 是该关系证据在正文中的字符区间（0 起，end 不超过正文长度）；无法确认区间的关系不要输出。""";

    private static final String SYSTEM_PROMPT = GraphExtractionPrompt.TEXT + FORMAT_INSTRUCTIONS;

    private final GraphModelClients clients;
    private final ModelEndpoint endpoint;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 运行时注入入口；另一个包内构造器仅供测试注入模型端点。 */
    @org.springframework.beans.factory.annotation.Autowired
    public LlmGraphExtractorV1(GraphModelClients clients) {
        this(clients, clients.extraction().orElse(null));
    }

    LlmGraphExtractorV1(GraphModelClients clients, ModelEndpoint endpoint) {
        this.clients = clients;
        this.endpoint = endpoint;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "大模型抽取 v1";
    }

    @Override
    public String promptVersion() {
        return GraphExtractionPrompt.VERSION;
    }

    @Override
    public PluginAvailability availability() {
        if (endpoint == null) {
            return PluginAvailability.unavailable(
                    "缺少配置：" + String.join("、", clients.missing("extraction")));
        }
        return PluginAvailability.ok();
    }

    @Override
    public GraphExtractionResult extract(GraphExtractionInput input) {
        if (endpoint == null) {
            throw GraphBuildFailure.retryable("MODEL_UNAVAILABLE", "抽取模型未配置");
        }
        String text;
        try {
            text = LlmPluginSupport.complete(endpoint, SYSTEM_PROMPT, userPrompt(input));
        } catch (Exception failure) {
            throw LlmPluginSupport.modelFailure(failure, "MODEL_UNAVAILABLE", "抽取模型暂不可用");
        }
        JsonNode root;
        try {
            root = mapper.readTree(LlmPluginSupport.stripCodeFence(text));
        } catch (Exception failure) {
            throw GraphBuildFailure.permanent("EXTRACTION_INVALID_JSON", "抽取结果不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw GraphBuildFailure.permanent("EXTRACTION_INVALID_JSON", "抽取结果不是合法 JSON");
        }
        List<GraphEntity> entities = parseEntities(root);
        List<GraphRelation> relations = parseRelations(root, input);
        return new GraphExtractionResult(input.sourceChunk(), entities, relations,
                id(), promptVersion(), input.entityLinkingVersion());
    }

    private static String userPrompt(GraphExtractionInput input) {
        StringBuilder user = new StringBuilder("正文：\n").append(input.content());
        if (input.parentContext() != null && !input.parentContext().isBlank()) {
            user.append("\n\n上级上下文：\n").append(input.parentContext());
        }
        return user.toString();
    }

    private List<GraphEntity> parseEntities(JsonNode root) {
        List<GraphEntity> entities = new ArrayList<>();
        for (JsonNode node : root.path("entities")) {
            String name = node.path("name").asText("").trim();
            if (name.isBlank()) {
                continue;
            }
            String entityId = node.path("id").asText(null);
            if (entityId != null && entityId.isBlank()) {
                entityId = null;
            }
            entities.add(new GraphEntity(entityId, name, parseType(node.path("type").asText("")),
                    parseStrings(node.path("aliases"))));
        }
        return entities;
    }

    private List<GraphRelation> parseRelations(JsonNode root, GraphExtractionInput input) {
        String sourceChunkId = input.sourceChunk().sourceChunkId();
        int contentLength = input.content().length();
        List<GraphRelation> relations = new ArrayList<>();
        for (JsonNode node : root.path("relations")) {
            String source = node.path("source").asText("").trim();
            String target = node.path("target").asText("").trim();
            if (source.isBlank() || target.isBlank()) {
                continue;
            }
            int start = node.path("start").asInt(0);
            int end = node.path("end").asInt(contentLength);
            // 越界或区间倒置的单条关系丢弃，不影响其余
            if (start < 0 || end > contentLength || end < start) {
                continue;
            }
            try {
                GraphSourceRef ref = new GraphSourceRef(sourceChunkId, start, end);
                GraphPredicate predicate = parsePredicate(node.path("predicate").asText(""));
                double confidence = node.path("confidence").asDouble(1.0);
                String relationId = sha256(sourceChunkId + "|" + source + "|"
                        + predicate.name() + "|" + target);
                relations.add(new GraphRelation(relationId, source, predicate, target,
                        confidence, List.of(ref)));
            } catch (IllegalArgumentException invalid) {
                // 置信度非法等单条构造失败时丢弃该条
            }
        }
        return relations;
    }

    private static GraphEntityType parseType(String type) {
        try {
            return GraphEntityType.valueOf(type.trim());
        } catch (IllegalArgumentException invalid) {
            return GraphEntityType.OTHER;
        }
    }

    private static GraphPredicate parsePredicate(String predicate) {
        try {
            return GraphPredicate.valueOf(predicate.trim());
        } catch (IllegalArgumentException invalid) {
            return GraphPredicate.RELATED_TO;
        }
    }

    private static List<String> parseStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode item : array) {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("运行时缺少 SHA-256", failure);
        }
    }
}
