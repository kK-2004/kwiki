package com.kwiki.infrastructure.ai.registry;

import com.kwiki.infrastructure.observability.SecretRedaction;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * 把模型 provider 的失败原因写进日志：上层只向用户暴露稳定错误码（如 answer-provider-failed），
 * 这里记录异常类型、HTTP 状态与 provider 返回的错误信息（如欠费、鉴权失败、限流），供运维定位。
 * 同步与流式调用都会回调；信息先脱敏再截断，绝不记录请求内容。
 */
public final class ModelCallErrorLogger implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(ModelCallErrorLogger.class);
    static final int MAX_DETAIL_CHARS = 1000;
    /** provider 常把收到的 key 原样回显（如 "Incorrect API key provided: sk-…"），通用脱敏规则覆盖不到。 */
    private static final Pattern BARE_KEY = Pattern.compile("\\b(?:sk|pk|ak)-[A-Za-z0-9_\\-]{8,}");

    private final String endpoint;
    private final String modelName;

    public ModelCallErrorLogger(String baseUrl, String modelName) {
        this.endpoint = host(baseUrl);
        this.modelName = modelName;
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        Throwable error = context.error();
        HttpException http = findHttp(error);
        Throwable reported = http != null ? http : rootCause(error);
        log.error("model provider call failed model={} endpoint={} errorType={} status={} detail={}",
                modelName, endpoint, reported.getClass().getSimpleName(),
                http == null ? "-" : String.valueOf(http.statusCode()), detail(reported));
    }

    private static HttpException findHttp(Throwable error) {
        for (Throwable current = error; current != null && current.getCause() != current; current = current.getCause()) {
            if (current instanceof HttpException http) return http;
        }
        return null;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        // 根因没有信息时退回到外层异常，避免只记下一个空类名
        return current.getMessage() == null ? error : current;
    }

    private static String detail(Throwable error) {
        String message = error.getMessage() == null ? "" : error.getMessage().replaceAll("\\s+", " ").strip();
        String redacted = BARE_KEY.matcher(SecretRedaction.redact(message)).replaceAll(SecretRedaction.REDACTED);
        return redacted.length() > MAX_DETAIL_CHARS ? redacted.substring(0, MAX_DETAIL_CHARS) + "…(truncated)" : redacted;
    }

    private static String host(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? baseUrl : host;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
