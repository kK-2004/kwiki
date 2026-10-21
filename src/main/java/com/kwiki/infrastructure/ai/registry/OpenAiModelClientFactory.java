package com.kwiki.infrastructure.ai.registry;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.infrastructure.ai.CancellableModelHttpClient;
import com.kwiki.infrastructure.ai.QwenEmbeddingClient;
import com.kwiki.usage.ModelUsageRecorder;

import com.fasterxml.jackson.databind.JsonNode;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import io.netty.channel.ChannelOption;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriUtils;

import reactor.netty.http.client.HttpClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * OpenAI-compatible 客户端工厂：对话客户端沿用 {@link CancellableModelHttpClient}（可取消、带链路追踪），
 * SDK 自身重试关闭（maxRetries=0），重试预算由应用侧掌握。thinking 非空时以
 * {@code enable_thinking} 自定义参数下发，为空时沿用 provider 默认行为。
 */
@Component
public class OpenAiModelClientFactory implements ModelClientFactory {

    private static final Set<String> OUTPUT_LIMIT_FIELDS = Set.of(
            "max_output_tokens", "maxOutputTokens",
            "max_completion_tokens", "maxCompletionTokens",
            "output_token_limit", "outputTokenLimit",
            "max_tokens", "maxTokens");

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final WebClient.Builder webClientBuilder;
    private final Optional<io.micrometer.tracing.Tracer> tracer;
    private final Optional<io.micrometer.tracing.propagation.Propagator> propagator;
    private final ModelUsageRecorder usageRecorder;

    public OpenAiModelClientFactory(WebClient.Builder webClientBuilder,
                                    Optional<io.micrometer.tracing.Tracer> tracer,
                                    Optional<io.micrometer.tracing.propagation.Propagator> propagator) {
        this(webClientBuilder, tracer, propagator, null);
    }

    @Autowired
    public OpenAiModelClientFactory(WebClient.Builder webClientBuilder,
                                    Optional<io.micrometer.tracing.Tracer> tracer,
                                    Optional<io.micrometer.tracing.propagation.Propagator> propagator,
                                    ObjectProvider<ModelUsageRecorder> usageRecorder) {
        this.webClientBuilder = webClientBuilder;
        this.tracer = tracer;
        this.propagator = propagator;
        this.usageRecorder = usageRecorder == null ? null : usageRecorder.getIfAvailable();
    }

    /** 错误日志始终挂载；用量监听器在装配了记录器时挂载。 */
    private List<ChatModelListener> listeners(ClientSpec spec) {
        List<ChatModelListener> listeners = new ArrayList<>();
        listeners.add(new ModelCallErrorLogger(spec.baseUrl(), spec.modelName()));
        if (usageRecorder != null) {
            listeners.add(new ModelUsageListener(usageRecorder, spec.step(), spec.providerName(), spec.modelName()));
        }
        return listeners;
    }

    @Override
    public ChatModel chat(ClientSpec spec) {
        var builder = OpenAiChatModel.builder()
                .baseUrl(spec.baseUrl())
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .temperature(spec.temperature())
                .maxRetries(0)
                .timeout(spec.timeout())
                .httpClientBuilder(httpClient())
                .listeners(listeners(spec));
        if (spec.maxTokens() != null) builder.maxTokens(spec.maxTokens());
        if (!spec.customParameters().isEmpty()) builder.customParameters(spec.customParameters());
        return builder.build();
    }

    @Override
    public StreamingChatModel streaming(ClientSpec spec) {
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(spec.baseUrl())
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .returnThinking(true)
                .strictJsonSchema(true)
                .temperature(spec.temperature())
                .timeout(spec.timeout())
                .httpClientBuilder(httpClient())
                .listeners(listeners(spec));
        if (spec.maxTokens() != null) builder.maxTokens(spec.maxTokens());
        if (!spec.customParameters().isEmpty()) builder.customParameters(spec.customParameters());
        return builder.build();
    }

    @Override
    public ChunkEmbeddingPort embedding(String baseUrl, String apiKey, Duration timeout, String modelName,
                                        int dimensions, int maxRetries, int batchSize, Map<String, Object> parameters) {
        return new QwenEmbeddingClient(webClient(baseUrl, apiKey, timeout), modelName, dimensions,
                batchSize, maxRetries, parameters);
    }

    @Override
    public ChunkEmbeddingPort embedding(String providerName, String baseUrl, String apiKey, Duration timeout,
                                        String modelName, int dimensions, int maxRetries, int batchSize,
                                        Map<String, Object> parameters) {
        return new QwenEmbeddingClient(webClient(baseUrl, apiKey, timeout), modelName, dimensions,
                batchSize, maxRetries, parameters, providerName, usageRecorder);
    }

    @Override
    public Integer probeOutputLimit(String baseUrl, String apiKey, Duration timeout, String modelName) {
        WebClient client = webClient(baseUrl, apiKey, timeout);
        Duration probeTimeout = timeout.compareTo(Duration.ofSeconds(15)) < 0 ? timeout : Duration.ofSeconds(15);
        for (String path : List.of("models/" + UriUtils.encodePathSegment(modelName, StandardCharsets.UTF_8), "models")) {
            try {
                JsonNode document = readJson(client, path, probeTimeout, baseUrl);
                Optional<Integer> limit = findOutputLimit(selectModel(document, modelName));
                if (limit.isPresent()) return limit.get();
            } catch (Exception ignored) {
                // 元数据不可用时回退到下一个端点，最终省略 max_tokens
            }
        }
        return null;
    }

    /** 列出 provider 的模型 id（GET /models）；用于连通性测试与模型发现。 */
    @Override
    public List<String> listModels(String baseUrl, String apiKey, Duration timeout) {
        JsonNode document = readJson(webClient(baseUrl, apiKey, timeout), "models", timeout, baseUrl);
        if (document == null || !document.path("data").isArray()) {
            throw new IllegalStateException("GET " + baseUrl.replaceAll("/+$", "")
                    + "/models 的响应不是 OpenAI 兼容的模型列表（缺少 data 数组）");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode model : document.path("data")) {
            String id = model.path("id").asText("");
            if (!id.isBlank()) ids.add(id);
        }
        return ids;
    }

    /**
     * 以字符串读取响应再解析 JSON：部分 provider 的 Content-Type 不规范（如 text/plain）。
     * 返回网页时多半是 baseUrl 指向了站点首页而不是 API 根路径，给出可操作的提示。
     */
    private static JsonNode readJson(WebClient client, String path, Duration timeout, String baseUrl) {
        String body = client.get().uri(path).retrieve().bodyToMono(String.class).block(timeout);
        String trimmed = body == null ? "" : body.strip();
        if (trimmed.startsWith("<")) {
            throw new IllegalStateException("provider 返回的是网页而不是 JSON，Base URL 可能缺少 /v1（例如 "
                    + baseUrl.replaceAll("/+$", "") + "/v1）");
        }
        try {
            return JSON.readTree(trimmed);
        } catch (Exception e) {
            throw new IllegalStateException("provider 的响应不是合法的 JSON");
        }
    }

    static JsonNode selectModel(JsonNode document, String modelName) {
        if (document != null && document.path("data").isArray()) {
            for (JsonNode candidate : document.path("data")) {
                if (modelName.equals(candidate.path("id").asText())) return candidate;
            }
        }
        return document;
    }

    static Optional<Integer> findOutputLimit(JsonNode node) {
        if (node == null || node.isNull()) return Optional.empty();
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (OUTPUT_LIMIT_FIELDS.contains(field.getKey()) && field.getValue().canConvertToInt()
                        && field.getValue().asInt() > 0) {
                    return Optional.of(field.getValue().asInt());
                }
            }
            fields = node.fields();
            while (fields.hasNext()) {
                Optional<Integer> nested = findOutputLimit(fields.next().getValue());
                if (nested.isPresent()) return nested;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                Optional<Integer> nested = findOutputLimit(item);
                if (nested.isPresent()) return nested;
            }
        }
        return Optional.empty();
    }

    private CancellableModelHttpClient.Builder httpClient() {
        return new CancellableModelHttpClient.Builder().tracing(tracer.orElse(null), propagator.orElse(null));
    }

    /** 基于 Spring 自动配置的 WebClient.Builder，provider 调用可被观测并自动传播 traceparent。 */
    private WebClient webClient(String baseUrl, String apiKey, Duration responseTimeout) {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(responseTimeout);
        return webClientBuilder.clone()
                .baseUrl(baseUrl.replaceAll("/+$", "") + "/")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }
}
