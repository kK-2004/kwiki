package com.kwiki.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kwiki.indexing.multimodal.ImageSummaryPort;
import com.kwiki.indexing.multimodal.MultimodalMetrics;
import com.kwiki.indexing.multimodal.ProtectedBlockProtocol;
import com.kwiki.indexing.multimodal.VisionSummaryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-compatible /chat/completions 的图片摘要客户端
 * （默认模型 qwen3.7-flash）。单条 user 消息按序携带
 * image_url（内容中心 CDN URL）与 text（版本化中文提示词）
 * 两个分段；生成参数固定（temperature 0）以保证索引一致性。
 * 仅对瞬时故障（429/5xx/超时/传输）做有界指数退避重试，其余
 * 4xx 与响应结构错误判为永久失败。并发受信号量限制；取消
 * 经由中断位与 RunContext 传播。日志、异常与指标绝不包含
 * API Key、完整 CDN URL 或请求体。
 */
public class QwenVisionSummaryClient implements ImageSummaryPort {

    private static final Logger log = LoggerFactory.getLogger(QwenVisionSummaryClient.class);

    /** 版本化中文检索摘要提示词；版本参与派生摘要持久身份。 */
    public static final String SUMMARY_PROMPT = "你是知识库检索系统的图片摘要助手。"
            + "请用一段简洁的中文事实描述这张图片，用于独立检索："
            + "说明图片类型与主题，描述可见的关键文字、实体、数值、趋势与关系。"
            + "只描述确实可见的内容，不要臆测看不见的信息，"
            + "不要输出 Markdown 图片语法或任何元数据标记，直接输出摘要文本。";

    /** 重试退避基数；测试可注入极小值。 */
    private static final Duration DEFAULT_BACKOFF_BASE = Duration.ofMillis(500);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final String model;
    private final int maxRetries;
    private final Duration requestTimeout;
    private final Semaphore concurrency;
    private final MultimodalMetrics metrics;
    private final Duration backoffBase;
    private final int maxSummaryChars;

    public QwenVisionSummaryClient(WebClient webClient, String model, int maxRetries,
                                   Duration requestTimeout, int concurrency,
                                   MultimodalMetrics metrics, int maxSummaryChars) {
        this(webClient, model, maxRetries, requestTimeout, concurrency, metrics,
                maxSummaryChars, DEFAULT_BACKOFF_BASE);
    }

    public QwenVisionSummaryClient(WebClient webClient, String model, int maxRetries,
                                   Duration requestTimeout, int concurrency,
                                   MultimodalMetrics metrics, int maxSummaryChars,
                                   Duration backoffBase) {
        this.webClient = webClient;
        this.model = model;
        this.maxRetries = Math.max(0, maxRetries);
        this.requestTimeout = requestTimeout;
        this.concurrency = new Semaphore(Math.max(1, concurrency));
        this.metrics = metrics;
        this.maxSummaryChars = maxSummaryChars;
        this.backoffBase = backoffBase;
    }

    @Override
    public String summarize(String cdnUrl) {
        VisionSummaryException lastTransient = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                checkCancelled();
                return callOnce(cdnUrl);
            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();
                if (!isTransient(status)) {
                    metrics.visionCall("rejected", Duration.ZERO);
                    throw new VisionSummaryException(VisionSummaryException.Category.PERMANENT,
                            "vision summary request rejected (HTTP " + status + ")");
                }
                lastTransient = new VisionSummaryException(VisionSummaryException.Category.TRANSIENT,
                        "vision summary endpoint unavailable (HTTP " + status + ")");
            } catch (VisionSummaryException e) {
                throw e;
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()
                        || e instanceof InterruptedException
                        || cancellationRelated(e)) {
                    throw new VisionSummaryException(VisionSummaryException.Category.TRANSIENT,
                            "vision summary cancelled");
                }
                lastTransient = new VisionSummaryException(VisionSummaryException.Category.TRANSIENT,
                        "vision summary transport failure");
            }
            if (attempt < maxRetries) {
                sleepBackoff(attempt);
            }
        }
        metrics.visionCall("transient-exhausted", Duration.ZERO);
        throw new VisionSummaryException(VisionSummaryException.Category.TRANSIENT,
                "vision summary retries exhausted after transient failures", lastTransient);
    }

    private String callOnce(String cdnUrl) throws InterruptedException {
        long start = System.nanoTime();
        try {
            checkCancelled();
            concurrency.acquire();
            try {
                Duration timeout = effectiveTimeout();
                VisionResponse response = webClient
                        .post()
                        .uri("/chat/completions")
                        .bodyValue(requestBody(cdnUrl))
                        .retrieve()
                        .bodyToMono(VisionResponse.class)
                        .block(timeout);
                String summary = extractSummary(response);
                metrics.visionCall("ok", Duration.ofNanos(System.nanoTime() - start));
                log.debug("vision summary completed model={} latencyMs={}",
                        model, Duration.ofNanos(System.nanoTime() - start).toMillis());
                return summary;
            } finally {
                concurrency.release();
            }
        } catch (VisionSummaryException invalid) {
            metrics.visionCall("invalid-response", Duration.ofNanos(System.nanoTime() - start));
            throw invalid;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw cancelled;
        }
        // WebClientResponseException 与传输错误原样上抛，由外层重试循环分类。
    }

    private VisionRequest requestBody(String cdnUrl) {
        return new VisionRequest(model, 0.0, List.of(new VisionMessage("user", List.of(
                new ImageUrlPart("image_url", new ImageUrl(cdnUrl)),
                new TextPart("text", SUMMARY_PROMPT)))));
    }

    /** 只接受非空 choices[0].message.content；空白、缺失、含标记或超长都是永久失败。 */
    private String extractSummary(VisionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().get(0) == null
                || response.choices().get(0).message() == null) {
            throw new VisionSummaryException(VisionSummaryException.Category.PERMANENT,
                    "vision response is missing the expected choice");
        }
        String content = response.choices().get(0).message().content();
        String normalized = content == null ? "" : content.replace('\r', ' ').strip();
        if (normalized.isEmpty()) {
            throw new VisionSummaryException(VisionSummaryException.Category.PERMANENT,
                    "vision response summary is blank");
        }
        if (normalized.contains(ProtectedBlockProtocol.START_PREFIX)
                || normalized.contains(ProtectedBlockProtocol.END_PREFIX)) {
            throw new VisionSummaryException(VisionSummaryException.Category.PERMANENT,
                    "vision response contains forbidden marker syntax");
        }
        if (normalized.length() > maxSummaryChars) {
            throw new VisionSummaryException(VisionSummaryException.Category.PERMANENT,
                    "vision response summary exceeds the configured limit");
        }
        return normalized;
    }

    private Duration effectiveTimeout() {
        var run = com.kwiki.rag.orchestration.RunContext.current();
        return run == null ? requestTimeout : run.timeout(requestTimeout);
    }

    private void checkCancelled() {
        var run = com.kwiki.rag.orchestration.RunContext.current();
        if (Thread.currentThread().isInterrupted()) {
            throw new VisionSummaryException(VisionSummaryException.Category.TRANSIENT,
                    "vision summary cancelled");
        }
        if (run != null) {
            run.check();
        }
    }

    private static boolean cancellationRelated(Throwable e) {
        return e instanceof com.kwiki.rag.orchestration.RunFailure
                || e.getClass().getSimpleName().contains("Cancel");
    }

    private void sleepBackoff(int attempt) {
        long backoffMs = Math.min(
                backoffBase.multipliedBy(1L << Math.min(attempt, 10)).toMillis(),
                MAX_BACKOFF.toMillis());
        try {
            TimeUnit.MILLISECONDS.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VisionSummaryException(VisionSummaryException.Category.TRANSIENT,
                    "vision summary cancelled during backoff");
        }
    }

    private static boolean isTransient(int status) {
        return status == 429 || status >= 500;
    }

    // ---------- 请求/响应 DTO：序列化键序即协议契约 ----------

    public record VisionRequest(String model, double temperature, List<VisionMessage> messages) {
    }

    public record VisionMessage(String role, List<Object> content) {
    }

    public record ImageUrlPart(String type, ImageUrl image_url) {
    }

    public record ImageUrl(String url) {
    }

    public record TextPart(String type, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VisionResponse(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String content) {
    }
}
