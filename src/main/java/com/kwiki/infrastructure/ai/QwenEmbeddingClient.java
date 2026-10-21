package com.kwiki.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.usage.ModelUsageContext;
import com.kwiki.usage.ModelUsageRecorder;
import com.kwiki.usage.UsageEvent;
import com.kwiki.usage.UsageSteps;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 OpenAI 兼容的 /embeddings 端点的 embedding 适配器（adapter），
 * 凭据来自模型目录。请求会批量处理，仅在截止时间（deadline）内对瞬时状态（429/5xx）
 * 重试，且每个返回的向量在使用前都会进行维度校验。错误中绝不
 * 包含 API key。
 *
 * <p>用量采集：每批 HTTP 请求记一条用量事件，调用数按批次计。
 */
public class QwenEmbeddingClient implements ChunkEmbeddingPort {

    /** provider 非瞬时拒绝（HTTP 4xx）；错误码形如 rejected-400。 */
    private static final String ERROR_REJECTED_PREFIX = "rejected-";
    /** 瞬时状态（429/5xx）重试耗尽仍未成功。 */
    private static final String ERROR_RETRIES_EXHAUSTED = "retries-exhausted";
    /** 返回向量的维度与配置维度不符（客户端自身校验，不是 provider 故障）。 */
    private static final String ERROR_DIMENSION_MISMATCH = "dimension-mismatch";
    /** 返回的向量条数与请求条数不符（provider 返回内容异常）。 */
    private static final String ERROR_COUNT_MISMATCH = "count-mismatch";
    /** 调用线程被中断。 */
    private static final String ERROR_CANCELLED = "cancelled";
    /** 其他请求失败（含运行上下文的取消或超时）。 */
    private static final String ERROR_REQUEST_FAILED = "request-failed";

    private final WebClient webClient;
    private final String model;
    private final int dimensions;
    private final int batchSize;
    private final int maxRetries;
    private final java.util.Map<String, Object> parameters;
    private final String providerName;
    private final ModelUsageRecorder recorder;

    public QwenEmbeddingClient(WebClient webClient, String model, int dimensions,
                               int batchSize, int maxRetries) {
        this(webClient, model, dimensions, batchSize, maxRetries, java.util.Map.of());
    }

    /** 由模型目录（{@code OpenAiModelClientFactory}）按 provider 与模型构建；parameters 透传进请求体。 */
    public QwenEmbeddingClient(WebClient webClient, String model, int dimensions,
                               int batchSize, int maxRetries, java.util.Map<String, Object> parameters) {
        this(webClient, model, dimensions, batchSize, maxRetries, parameters, null, null);
    }

    /**
     * 带 provider 名与用量记录器的构建：provider 名用于用量归属，recorder 为 null 时不采集。
     */
    public QwenEmbeddingClient(WebClient webClient, String model, int dimensions,
                               int batchSize, int maxRetries, java.util.Map<String, Object> parameters,
                               String providerName, ModelUsageRecorder recorder) {
        this.webClient = webClient;
        this.model = model;
        this.dimensions = dimensions;
        this.batchSize = Math.max(1, batchSize);
        this.maxRetries = Math.max(0, maxRetries);
        this.parameters = parameters == null ? java.util.Map.of() : java.util.Map.copyOf(parameters);
        this.providerName = providerName;
        this.recorder = recorder;
    }

    @Override
    public String cacheIdentity() {
        return model + ":" + dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> batch = texts.subList(start, Math.min(start + batchSize, texts.size()));
            result.addAll(embedBatch(batch));
        }
        return result;
    }

    /** 一次批量请求的结果：向量与 provider 回报的输入 token 数。 */
    private record BatchResult(List<float[]> vectors, int promptTokens) {
    }

    /**
     * 携带稳定错误码的失败：把 provider 拒绝、重试耗尽、维度/数量不匹配等区分开，
     * 便于用量聚合按失败原因统计（错误码即 {@code UsageEvent.errorType()}）。
     */
    private static final class EmbeddingFailure extends IllegalStateException {

        private final String errorType;

        EmbeddingFailure(String errorType, String message) {
            super(message);
            this.errorType = errorType;
        }

        EmbeddingFailure(String errorType, String message, Throwable cause) {
            super(message, cause);
            this.errorType = errorType;
        }
    }

    /** 记录一次批量请求的用量（失败也记录），任何采集异常都不影响 embedding 调用。 */
    private List<float[]> embedBatch(List<String> batch) {
        long started = System.nanoTime();
        try {
            BatchResult result = embedWithRetry(batch);
            recordUsage(started, result.promptTokens(), true, null);
            return result.vectors();
        } catch (RuntimeException e) {
            recordUsage(started, 0, false, errorType(e));
            throw e;
        }
    }

    /** 瞬时状态（429/5xx）按上限重试，非瞬时错误立即失败。 */
    private BatchResult embedWithRetry(List<String> batch) {
        WebClientResponseException lastTransient = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                var run = com.kwiki.rag.orchestration.RunContext.current();
                if (Thread.currentThread().isInterrupted())
                    throw new EmbeddingFailure(ERROR_CANCELLED, "embedding cancelled");
                if (run != null) run.check();
                return callOnce(batch);
            } catch (WebClientResponseException e) {
                if (!isTransient(e.getStatusCode().value())) {
                    throw new EmbeddingFailure(ERROR_REJECTED_PREFIX + e.getStatusCode().value(),
                            "embedding request rejected: " + e.getStatusCode().value()
                                    + providerReason(e) + " (batch=" + batch.size() + ")");
                }
                lastTransient = e;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new EmbeddingFailure(requestFailedCode(e), "embedding request failed", e);
            }
        }
        throw new EmbeddingFailure(ERROR_RETRIES_EXHAUSTED,
                "embedding retries exhausted after transient failures", lastTransient);
    }

    /** 失败码：稳定短码，供用量聚合区分失败原因。 */
    private static String errorType(RuntimeException failure) {
        if (failure instanceof EmbeddingFailure coded) return coded.errorType;
        return ERROR_REQUEST_FAILED;
    }

    /** 运行上下文的取消/超时自带稳定码，其余归为请求失败。 */
    private static String requestFailedCode(Exception failure) {
        if (failure instanceof com.kwiki.rag.orchestration.RunFailure run && run.getMessage() != null) {
            return run.getMessage();
        }
        return ERROR_REQUEST_FAILED;
    }

    private BatchResult callOnce(List<String> batch) {
        EmbeddingResponse response =
                webClient
                        .post()
                        .uri("/embeddings")
                        .bodyValue(requestBody(batch))
                        .retrieve()
                        .bodyToMono(EmbeddingResponse.class)
                        .block(
                                com.kwiki.rag.orchestration.RunContext.current() == null
                                        ? Duration.ofSeconds(60)
                                        : com.kwiki.rag.orchestration.RunContext.current()
                                                .timeout(Duration.ofSeconds(60)));
        if (response == null || response.data() == null || response.data().size() != batch.size()) {
            throw new EmbeddingFailure(ERROR_COUNT_MISMATCH, "embedding response count mismatch");
        }
        List<float[]> vectors = new ArrayList<>(batch.size());
        for (EmbeddingItem item : response.data()) {
            float[] vector = item.embedding();
            if (vector == null || vector.length != dimensions) {
                throw new EmbeddingFailure(ERROR_DIMENSION_MISMATCH,
                        "embedding dimension mismatch: expected " + dimensions);
            }
            vectors.add(vector);
        }
        int promptTokens = response.usage() == null || response.usage().promptTokens() == null
                ? 0 : response.usage().promptTokens();
        return new BatchResult(vectors, promptTokens);
    }

    /**
     * 记录一次批量请求的用量；步骤默认查询向量，索引任务通过 ModelUsageContext 覆盖为索引向量。
     * 每批 HTTP 请求记一条用量事件，调用数按批次计。
     */
    private void recordUsage(long startedNanos, int promptTokens, boolean success, String errorType) {
        if (recorder == null) return;
        try {
            var owner = ModelUsageContext.resolve();
            String step = owner.step() == null ? UsageSteps.QUERY_EMBEDDING : owner.step();
            recorder.record(new UsageEvent(java.time.Instant.now(), owner.source(), step, providerName, model,
                    owner.userId(), owner.requestId(), promptTokens, 0,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
                    success, errorType));
        } catch (RuntimeException ignored) {
            // 采集失败不影响 embedding
        }
    }

    private java.util.Map<String, Object> requestBody(List<String> batch) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>(parameters);
        body.put("model", model);
        body.put("input", List.copyOf(batch));
        return body;
    }

    /** provider 返回的错误原因（脱敏、截断）；拿不到时为空串。 */
    private static String providerReason(WebClientResponseException e) {
        String body;
        try {
            body = e.getResponseBodyAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
        if (body == null || body.isBlank()) return "";
        String reason = com.kwiki.infrastructure.observability.SecretRedaction.redact(body.strip())
                .replaceAll("\\s+", " ");
        return " " + (reason.length() > 300 ? reason.substring(0, 300) + "…" : reason);
    }

    private static boolean isTransient(int status) {
        return status == 429 || status >= 500;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingResponse(List<EmbeddingItem> data, EmbeddingUsage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingItem(int index, float[] embedding) {}

    /** provider 回报的 token 用量；缺失时按 0 记。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingUsage(@JsonProperty("prompt_tokens") Integer promptTokens,
                                 @JsonProperty("total_tokens") Integer totalTokens) {}
}
