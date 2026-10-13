package com.kwiki.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;

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
 */
public class QwenEmbeddingClient implements ChunkEmbeddingPort {

    private final WebClient webClient;
    private final String model;
    private final int dimensions;
    private final int batchSize;
    private final int maxRetries;
    private final java.util.Map<String, Object> parameters;

    public QwenEmbeddingClient(WebClient webClient, String model, int dimensions,
                               int batchSize, int maxRetries) {
        this(webClient, model, dimensions, batchSize, maxRetries, java.util.Map.of());
    }

    /** 由模型目录（{@code OpenAiModelClientFactory}）按 provider 与模型构建；parameters 透传进请求体。 */
    public QwenEmbeddingClient(WebClient webClient, String model, int dimensions,
                               int batchSize, int maxRetries, java.util.Map<String, Object> parameters) {
        this.webClient = webClient;
        this.model = model;
        this.dimensions = dimensions;
        this.batchSize = Math.max(1, batchSize);
        this.maxRetries = Math.max(0, maxRetries);
        this.parameters = parameters == null ? java.util.Map.of() : java.util.Map.copyOf(parameters);
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

    private List<float[]> embedBatch(List<String> batch) {
        WebClientResponseException lastTransient = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                var run = com.kwiki.rag.orchestration.RunContext.current();
                if (Thread.currentThread().isInterrupted())
                    throw new IllegalStateException("embedding cancelled");
                if (run != null) run.check();
                return callOnce(batch);
            } catch (WebClientResponseException e) {
                if (!isTransient(e.getStatusCode().value())) {
                    throw new IllegalStateException("embedding request rejected: " + e.getStatusCode().value()
                            + providerReason(e) + " (batch=" + batch.size() + ")", null);
                }
                lastTransient = e;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("embedding request failed", e);
            }
        }
        throw new IllegalStateException(
                "embedding retries exhausted after transient failures", lastTransient);
    }

    private List<float[]> callOnce(List<String> batch) {
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
            throw new IllegalStateException("embedding response count mismatch");
        }
        List<float[]> vectors = new ArrayList<>(batch.size());
        for (EmbeddingItem item : response.data()) {
            float[] vector = item.embedding();
            if (vector == null || vector.length != dimensions) {
                throw new IllegalStateException(
                        "embedding dimension mismatch: expected " + dimensions);
            }
            vectors.add(vector);
        }
        return vectors;
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
    public record EmbeddingResponse(List<EmbeddingItem> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingItem(int index, float[] embedding) {}
}
