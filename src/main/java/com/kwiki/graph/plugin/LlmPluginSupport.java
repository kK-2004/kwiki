package com.kwiki.graph.plugin;

import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.model.ModelEndpoint;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;

import java.util.List;

/** 两个大模型插件共享的调用与错误分类逻辑。 */
final class LlmPluginSupport {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LlmPluginSupport.class);

    /** Cloudflare 等网关在源站长时间不返回首字节时的状态码（约 100 秒）。 */
    static final int GATEWAY_RESPONSE_TIMEOUT = 524;
    /** 流式等待的兜底余量：传输层自身按 timeout 截止，这里只防回调永不到达。 */
    private static final java.time.Duration STREAM_WAIT_MARGIN = java.time.Duration.ofSeconds(30);
    private static final java.time.Duration DEFAULT_STREAM_WAIT = java.time.Duration.ofMinutes(10);

    private LlmPluginSupport() {
    }

    /**
     * 经全局限流器调用模型，返回助手消息文本。默认非流式；网关返回 524（源站在约 100 秒内
     * 没有返回任何字节，常见于思考型模型或长输出）时立即改用流式重发本次请求——流式会持续
     * 下发字节，不会触发网关的首字节超时——并记住该端点，此后直接走流式。
     */
    static String complete(ModelEndpoint endpoint, String system, String user) {
        List<dev.langchain4j.data.message.ChatMessage> messages =
                List.of(SystemMessage.from(system), UserMessage.from(user));
        if (endpoint.prefersStreaming()) {
            return endpoint.limiter().call(() -> stream(endpoint, messages));
        }
        try {
            String text = endpoint.limiter().call(() -> endpoint.model().chat(messages).aiMessage().text());
            return text == null ? "" : text;
        } catch (RuntimeException failure) {
            if (endpoint.streaming() == null || !gatewayResponseTimeout(failure)) {
                throw failure;
            }
            if (endpoint.switchToStreaming()) {
                log.info("model {} returned {} (gateway response timeout) for a non-streaming call; "
                        + "switching graph calls to streaming", endpoint.modelName(), GATEWAY_RESPONSE_TIMEOUT);
            }
            return endpoint.limiter().call(() -> stream(endpoint, messages));
        }
    }

    /** 失败链上是否有网关 524。 */
    static boolean gatewayResponseTimeout(Throwable failure) {
        for (Throwable current = failure; current != null;
             current = current.getCause() == current ? null : current.getCause()) {
            if (current instanceof HttpException http && http.statusCode() == GATEWAY_RESPONSE_TIMEOUT) {
                return true;
            }
        }
        return false;
    }

    /** 流式调用并拼出完整回答；思考内容不计入。传输层的超时与错误原样上抛，供重试分类。 */
    private static String stream(ModelEndpoint endpoint,
                                 List<dev.langchain4j.data.message.ChatMessage> messages) {
        java.util.concurrent.CompletableFuture<String> done = new java.util.concurrent.CompletableFuture<>();
        StringBuilder partial = new StringBuilder();
        endpoint.streaming().chat(messages, new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String text) {
                if (text != null) {
                    synchronized (partial) {
                        partial.append(text);
                    }
                }
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
                String text = response == null || response.aiMessage() == null ? null : response.aiMessage().text();
                synchronized (partial) {
                    // 完成回调通常带聚合后的全文；为空时用分片拼接结果兜底
                    done.complete(text != null && !text.isBlank() ? text : partial.toString());
                }
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }
        });
        java.time.Duration wait = endpoint.timeout() == null ? DEFAULT_STREAM_WAIT
                : endpoint.timeout().plus(STREAM_WAIT_MARGIN);
        try {
            return done.get(wait.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
        } catch (java.util.concurrent.TimeoutException timedOut) {
            throw new IllegalStateException("流式模型调用未在 " + wait.toSeconds() + " 秒内结束", timedOut);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("模型调用被中断");
        }
    }

    /**
     * 模型调用异常分类：超时 / 429 / 5xx 等传输层错误可重试；其余 4xx 视为
     * 请求被拒绝（不重试）；未知异常保守按可重试处理。
     */
    static GraphBuildFailure modelFailure(Exception failure, String unavailableCode,
                                          String unavailableSummary) {
        if (failure instanceof GraphBuildFailure already) {
            return already;
        }
        if (timedOut(failure)) {
            return GraphBuildFailure.retryable(GraphBuildFailure.MODEL_TIMEOUT,
                    unavailableSummary + "：模型调用超时");
        }
        if (transientModelError(failure)) {
            return GraphBuildFailure.retryable(unavailableCode, unavailableSummary);
        }
        return GraphBuildFailure.permanent("MODEL_REQUEST_REJECTED", "模型请求被拒绝，不重试");
    }

    /**
     * 超时：langchain4j 的 TimeoutException，或传输层抛出的 RunFailure("timeout")、
     * JDK 的 java.util.concurrent.TimeoutException / HttpTimeoutException（可能被包装在 cause 链里）。
     */
    static boolean timedOut(Throwable failure) {
        for (Throwable current = failure; current != null;
             current = current.getCause() == current ? null : current.getCause()) {
            if (current instanceof TimeoutException
                    || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            if (current instanceof com.kwiki.rag.orchestration.RunFailure runFailure
                    && "timeout".equals(runFailure.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean transientModelError(Throwable failure) {
        if (failure instanceof TimeoutException || failure instanceof RateLimitException) {
            return true;
        }
        if (failure instanceof HttpException http) {
            return http.statusCode() == 429 || http.statusCode() >= 500;
        }
        // 未知异常（网络中断、IO 等）保守按可重试处理；参数类异常不会因重试而好转
        return !(failure instanceof IllegalArgumentException);
    }

    /** 去掉模型输出中的 ```json 围栏。 */
    static String stripCodeFence(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            trimmed = firstLineEnd >= 0 ? trimmed.substring(firstLineEnd + 1) : "";
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}
