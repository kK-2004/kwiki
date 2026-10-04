package com.kwiki.graph.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.kwiki.graph.CommunitySummary;
import com.kwiki.graph.CommunitySummaryInput;
import com.kwiki.graph.GraphSourceRef;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.model.GraphModelClients;
import com.kwiki.graph.model.ModelEndpoint;
import com.kwiki.indexing.plugin.PluginAvailability;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 首个大模型社区摘要插件：只依据代表实体、关系与来源内容生成中文摘要。 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class LlmCommunitySummarizerV1 implements CommunitySummarizerPlugin {

    public static final String ID = "kwiki-summarizer-1";
    public static final String PROMPT_VERSION = "community-summary-v1";

    private static final String SYSTEM_PROMPT = """
            你是知识图谱社区摘要助手。只依据输入的代表实体、代表关系和来源内容生成简体中文摘要，\
            不要引入输入之外的事实。输出格式：只输出一个 JSON 对象，不要输出 JSON 以外的任何文字。
            {"title":"社区标题","summary":"两到三句话概括这个社区围绕的主题","keywords":["关键词"],\
            "coreConcepts":["核心概念"],"importantRelations":["重要关系描述"],\
            "questionTypes":["适合用该社区回答的问题类型"],"sourceRefs":[{"sourceChunkId":"来源块身份","start":0,"end":10}]}
            sourceRefs 只能引用输入中给出的来源块身份；start/end 是被引用内容在来源块正文中的字符区间。""";

    private final GraphModelClients clients;
    private final ModelEndpoint endpoint;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 运行时注入入口；另一个包内构造器仅供测试注入模型端点。 */
    @org.springframework.beans.factory.annotation.Autowired
    public LlmCommunitySummarizerV1(GraphModelClients clients) {
        this(clients, clients.summary().orElse(null));
    }

    LlmCommunitySummarizerV1(GraphModelClients clients, ModelEndpoint endpoint) {
        this.clients = clients;
        this.endpoint = endpoint;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "大模型摘要 v1";
    }

    @Override
    public String promptVersion() {
        return PROMPT_VERSION;
    }

    @Override
    public String modelName() {
        return endpoint == null ? "" : endpoint.modelName();
    }

    @Override
    public PluginAvailability availability() {
        if (endpoint == null) {
            return PluginAvailability.unavailable(
                    "缺少配置：" + String.join("、", clients.missing("summary")));
        }
        return PluginAvailability.ok();
    }

    @Override
    public CommunitySummary summarize(CommunitySummaryInput input) {
        if (endpoint == null) {
            throw GraphBuildFailure.retryable("SUMMARY_MODEL_UNAVAILABLE", "摘要模型未配置");
        }
        String text;
        try {
            text = LlmPluginSupport.complete(endpoint, SYSTEM_PROMPT, userPrompt(input));
        } catch (Exception failure) {
            throw LlmPluginSupport.modelFailure(failure, "SUMMARY_MODEL_UNAVAILABLE", "摘要模型暂不可用");
        }
        JsonNode root;
        try {
            root = mapper.readTree(LlmPluginSupport.stripCodeFence(text));
        } catch (Exception failure) {
            throw GraphBuildFailure.permanent("SUMMARY_INVALID_JSON", "摘要结果不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw GraphBuildFailure.permanent("SUMMARY_INVALID_JSON", "摘要结果不是合法 JSON");
        }
        String title = root.path("title").asText("").trim();
        String summary = root.path("summary").asText("").trim();
        if (title.isBlank() || summary.isBlank()) {
            throw GraphBuildFailure.permanent("SUMMARY_INVALID_JSON", "摘要结果缺少标题或正文");
        }
        return new CommunitySummary(title, summary,
                parseStrings(root.path("keywords")),
                parseStrings(root.path("coreConcepts")),
                parseStrings(root.path("importantRelations")),
                parseStrings(root.path("questionTypes")),
                parseSourceRefs(root.path("sourceRefs")));
    }

    private static String userPrompt(CommunitySummaryInput input) {
        StringBuilder user = new StringBuilder();
        user.append("社区编号：").append(input.communityId()).append("\n\n代表实体：\n");
        for (var entity : input.representativeEntities()) {
            user.append("- ").append(entity.canonicalName())
                    .append("（").append(entity.entityType()).append("）\n");
        }
        user.append("\n代表关系：\n");
        for (var relation : input.representativeRelations()) {
            user.append("- ").append(relation.sourceEntityId())
                    .append(" -[").append(relation.predicate()).append("]-> ")
                    .append(relation.targetEntityId()).append("\n");
        }
        user.append("\n来源块身份（sourceRefs 只能引用这些 sourceChunkId）：\n");
        for (var source : input.representativeSources()) {
            user.append("- ").append(source.sourceChunkId()).append("\n");
        }
        if (!input.content().isBlank()) {
            user.append("\n汇总正文：\n").append(input.content());
        }
        return user.toString();
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

    private static List<GraphSourceRef> parseSourceRefs(JsonNode array) {
        List<GraphSourceRef> refs = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode item : array) {
                String sourceChunkId = item.path("sourceChunkId").asText("").trim();
                if (sourceChunkId.isBlank()) {
                    continue;
                }
                try {
                    refs.add(new GraphSourceRef(sourceChunkId,
                            item.path("start").asInt(0), item.path("end").asInt(0)));
                } catch (IllegalArgumentException invalid) {
                    // 单条来源引用无效时丢弃，不影响其余
                }
            }
        }
        return refs;
    }
}
