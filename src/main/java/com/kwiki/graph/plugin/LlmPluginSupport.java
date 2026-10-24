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

    private LlmPluginSupport() {
    }

    /** 经全局限流器调用模型，返回助手消息文本。 */
    static String complete(ModelEndpoint endpoint, String system, String user) {
        String text = endpoint.limiter().call(() -> endpoint.model()
                .chat(List.of(SystemMessage.from(system), UserMessage.from(user)))
                .aiMessage().text());
        return text == null ? "" : text;
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
