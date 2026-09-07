package com.kwiki.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.infrastructure.config.ExternalServicesProperties;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Qwen text-embedding-v4 adapter over the OpenAI-compatible /embeddings endpoint using the
 * kwiki-specific credential. Requests are batched, retried only for transient statuses (429/5xx)
 * within the deadline, and every returned vector is dimension-checked before use. Errors never
 * include the API key.
 */
@Component
public class QwenEmbeddingClient implements ChunkEmbeddingPort {

    private final WebClient webClient;
    private final String model;
    private final int dimensions;
    private final int batchSize;
    private final int maxRetries;

    public QwenEmbeddingClient(
            @Qualifier("kwikiEmbeddingWebClient") WebClient webClient,
            ExternalServicesProperties properties,
            @Value("${kwiki.qwen-embedding.batch-size:25}") int batchSize,
            @Value("${kwiki.qwen-embedding.max-retries:2}") int maxRetries) {
        this.webClient = webClient;
        this.model = properties.qwenEmbedding().model();
        this.dimensions = properties.qwenEmbedding().dimensions();
        this.batchSize = Math.max(1, batchSize);
        this.maxRetries = Math.max(0, maxRetries);
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
                    throw new IllegalStateException(
                            "embedding request rejected: " + e.getStatusCode().value(), null);
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
                        .bodyValue(new EmbeddingRequest(model, List.copyOf(batch)))
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

    private static boolean isTransient(int status) {
        return status == 429 || status >= 500;
    }

    public record EmbeddingRequest(String model, List<String> input) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingResponse(List<EmbeddingItem> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingItem(int index, float[] embedding) {}
}
