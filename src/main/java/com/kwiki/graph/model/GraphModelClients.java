package com.kwiki.graph.model;

import com.kwiki.graph.config.GraphModelProperties;
import com.kwiki.infrastructure.ai.CancellableModelHttpClient;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 图构建离线模型客户端：抽取与摘要两个端点在构造器中各建一次。
 * 连接项（baseUrl / apiKey / model / requestTimeout）未配置时沿用问答模型；
 * 客户端不注册为 ChatModel / StreamingChatModel Bean，避免与问答模型按类型注入冲突。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphModelClients {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final int EXTRACTION_MAX_CONCURRENCY = 4;
    private static final double EXTRACTION_QPS = 5.0;
    private static final int SUMMARY_MAX_CONCURRENCY = 2;
    private static final double SUMMARY_QPS = 2.0;
    private static final int DEFAULT_MAX_RETRIES = 2;

    private final ExternalServicesProperties external;
    private final GraphModelProperties.Model extractionConfigured;
    private final GraphModelProperties.Model summaryConfigured;
    private final Optional<io.micrometer.tracing.Tracer> tracer;
    private final Optional<io.micrometer.tracing.propagation.Propagator> propagator;
    private final Optional<ModelEndpoint> extraction;
    private final Optional<ModelEndpoint> summary;

    public GraphModelClients(GraphModelProperties models, ExternalServicesProperties external) {
        this(models, external, Optional.empty(), Optional.empty());
    }

    @Autowired
    public GraphModelClients(GraphModelProperties models, ExternalServicesProperties external,
                             Optional<io.micrometer.tracing.Tracer> tracer,
                             Optional<io.micrometer.tracing.propagation.Propagator> propagator) {
        this.external = external;
        this.tracer = tracer;
        this.propagator = propagator;
        this.extractionConfigured = models == null ? null : models.extraction();
        this.summaryConfigured = models == null ? null : models.summary();
        this.extraction = endpoint(extractionConfigured, "extraction",
                EXTRACTION_MAX_CONCURRENCY, EXTRACTION_QPS);
        this.summary = endpoint(summaryConfigured, "summary", SUMMARY_MAX_CONCURRENCY, SUMMARY_QPS);
    }

    public Optional<ModelEndpoint> extraction() {
        return extraction;
    }

    public Optional<ModelEndpoint> summary() {
        return summary;
    }

    /** 合并问答模型后仍缺的配置键（如 kwiki.graph.models.extraction.api-key），用于可用性原因。 */
    public List<String> missing(String purpose) {
        String prefix = switch (purpose == null ? "" : purpose) {
            case "extraction" -> "kwiki.graph.models.extraction";
            case "summary" -> "kwiki.graph.models.summary";
            default -> throw new IllegalArgumentException("未知的模型用途：" + purpose);
        };
        GraphModelProperties.Model configured = "extraction".equals(purpose)
                ? extractionConfigured : summaryConfigured;
        List<String> missing = new ArrayList<>();
        if (isBlank(merged(configured, GraphModelProperties.Model::baseUrl, ExternalServicesProperties.AnswerLlm::baseUrl))) {
            missing.add(prefix + ".base-url");
        }
        if (isBlank(merged(configured, GraphModelProperties.Model::apiKey, ExternalServicesProperties.AnswerLlm::apiKey))) {
            missing.add(prefix + ".api-key");
        }
        if (isBlank(merged(configured, GraphModelProperties.Model::model, ExternalServicesProperties.AnswerLlm::model))) {
            missing.add(prefix + ".model");
        }
        return List.copyOf(missing);
    }

    private Optional<ModelEndpoint> endpoint(GraphModelProperties.Model configured, String purpose,
                                             int defaultConcurrency, double defaultQps) {
        String baseUrl = merged(configured, GraphModelProperties.Model::baseUrl,
                ExternalServicesProperties.AnswerLlm::baseUrl);
        String apiKey = merged(configured, GraphModelProperties.Model::apiKey,
                ExternalServicesProperties.AnswerLlm::apiKey);
        String modelName = merged(configured, GraphModelProperties.Model::model,
                ExternalServicesProperties.AnswerLlm::model);
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(modelName)) {
            return Optional.empty();
        }
        ExternalServicesProperties.AnswerLlm answer = external == null ? null : external.answerLlm();
        Duration timeout = configured != null && configured.requestTimeout() != null
                ? configured.requestTimeout()
                : answer != null && answer.requestTimeout() != null
                        ? answer.requestTimeout() : DEFAULT_TIMEOUT;
        int concurrency = configured != null && configured.maxConcurrency() != null
                ? configured.maxConcurrency() : defaultConcurrency;
        double qps = configured != null && configured.qps() != null
                ? configured.qps() : defaultQps;
        int maxRetries = configured != null && configured.maxRetries() != null
                ? configured.maxRetries() : DEFAULT_MAX_RETRIES;
        var model = dev.langchain4j.model.openai.OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.0)
                .maxRetries(0)
                .timeout(timeout)
                .httpClientBuilder(new CancellableModelHttpClient.Builder()
                        .tracing(tracer.orElse(null), propagator.orElse(null)))
                .build();
        return Optional.of(new ModelEndpoint(model, new RateLimiter(concurrency, qps),
                maxRetries, modelName));
    }

    private String merged(GraphModelProperties.Model configured,
                          java.util.function.Function<GraphModelProperties.Model, String> field,
                          java.util.function.Function<ExternalServicesProperties.AnswerLlm, String> answerField) {
        String value = configured == null ? null : field.apply(configured);
        if (!isBlank(value)) {
            return value;
        }
        ExternalServicesProperties.AnswerLlm answer = external == null ? null : external.answerLlm();
        return answer == null ? null : answerField.apply(answer);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
