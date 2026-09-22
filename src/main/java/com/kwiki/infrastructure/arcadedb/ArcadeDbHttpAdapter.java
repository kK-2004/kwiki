package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.config.ArcadeDbProperties;
import com.kwiki.infrastructure.observability.SecretRedaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * ArcadeDB HTTP 传输适配器。数据库路径、命令体和凭据均在此边界内转换，
 * 上层只传递参数化语句，不接触 RID、HTTP 响应对象或凭据。
 */
public final class ArcadeDbHttpAdapter {

    private static final Logger log = LoggerFactory.getLogger(ArcadeDbHttpAdapter.class);
    private final ArcadeDbProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public ArcadeDbHttpAdapter(ArcadeDbProperties properties, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    /** 执行参数化 SQL 查询；statement 由受信任的基础设施服务提供。 */
    public ArcadeDbResponse query(String database, String statement, Map<String, Object> parameters) {
        return execute("/api/v1/query/" + pathSegment(database) + "/sql", "POST",
                commandBody(statement, parameters), ArcadeDbTimeoutKind.QUERY);
    }

    /** 执行参数化写命令；建库和算法等高风险操作只由构建协调路径调用。 */
    public ArcadeDbResponse command(String database, String statement, Map<String, Object> parameters,
                                    ArcadeDbTimeoutKind timeoutKind) {
        return execute("/api/v1/command/" + pathSegment(database) + "/sql", "POST",
                commandBody(statement, parameters), timeoutKind);
    }

    /** 探测服务端健康状态。 */
    public ArcadeDbResponse health() {
        return execute("/api/v1/server", "GET", null, ArcadeDbTimeoutKind.QUERY);
    }

    /** 查询远端长任务状态，未知状态由调用方保留槽位处理。 */
    public ArcadeDbResponse probeRemoteOperation(String operationId) {
        return execute("/api/v1/operation/" + pathSegment(operationId), "GET", null,
                ArcadeDbTimeoutKind.QUERY);
    }

    /** 请求远端取消长任务；是否已完成仍需随后调用状态探测确认。 */
    public ArcadeDbResponse cancelRemoteOperation(String operationId) {
        return execute("/api/v1/operation/" + pathSegment(operationId), "DELETE", null,
                ArcadeDbTimeoutKind.QUERY);
    }

    /** 供构建协调器使用的异步调用；取消 future 不会伪造远端已停止。 */
    public CompletableFuture<ArcadeDbResponse> submit(String path, String method,
                                                       Map<String, Object> body,
                                                       ArcadeDbTimeoutKind timeoutKind) {
        String payload = body == null ? null : json(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(timeout(timeoutKind))
                .header("Accept", "application/json");
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        String token = properties.token();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest request = builder.method(method,
                        payload == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error != null) {
                        throw new CompletionException(classify(error, null));
                    }
                    ArcadeDbResponse result = new ArcadeDbResponse(response.statusCode(), response.body());
                    if (!result.isSuccessful()) {
                        throw new CompletionException(classify(null, result));
                    }
                    return result;
                });
    }

    private ArcadeDbResponse execute(String path, String method, Map<String, Object> body,
                                     ArcadeDbTimeoutKind timeoutKind) {
        CompletableFuture<ArcadeDbResponse> request = submit(path, method, body, timeoutKind);
        try {
            return request.get(timeout(timeoutKind).toMillis() + 250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            request.cancel(true);
            throw classify(e, null);
        } catch (java.util.concurrent.TimeoutException e) {
            request.cancel(true);
            throw new ArcadeDbClientException(ArcadeDbFailureCategory.TIMEOUT,
                    "ArcadeDB 请求超时", null, e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw unwrap(e);
        }
    }

    private ArcadeDbClientException unwrap(java.util.concurrent.ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof CompletionException completion && completion.getCause() != null) {
            cause = completion.getCause();
        }
        if (cause instanceof ArcadeDbClientException clientException) {
            return clientException;
        }
        return classify(cause, null);
    }

    private ArcadeDbClientException classify(Throwable error, ArcadeDbResponse response) {
        Throwable root = rootCause(error);
        Integer status = response == null ? null : response.statusCode();
        ArcadeDbFailureCategory category;
        if (status != null && (status == 401 || status == 403)) {
            category = ArcadeDbFailureCategory.AUTHENTICATION;
        } else if (root instanceof java.net.http.HttpTimeoutException
                || root instanceof java.util.concurrent.TimeoutException) {
            category = ArcadeDbFailureCategory.TIMEOUT;
        } else if (root instanceof ConnectException || root instanceof IOException) {
            category = ArcadeDbFailureCategory.CONNECTIVITY;
        } else if (status != null) {
            category = ArcadeDbFailureCategory.REMOTE;
        } else {
            category = ArcadeDbFailureCategory.UNKNOWN;
        }
        String message = status == null ? "ArcadeDB 请求失败" : "ArcadeDB 返回 HTTP " + status;
        if (root != null && root.getMessage() != null) {
            String redacted = SecretRedaction.redact(root.getMessage());
            if (redacted != null && !redacted.isBlank()) {
                message += ": " + truncate(redacted);
            }
        }
        log.warn("ArcadeDB HTTP 调用失败 category={} status={} error={}", category, status,
                truncate(SecretRedaction.redact(message)));
        return new ArcadeDbClientException(category, message, status, error);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private URI uri(String path) {
        if (properties.endpoint() == null) {
            throw new ArcadeDbClientException(ArcadeDbFailureCategory.CONFIGURATION,
                    "ArcadeDB endpoint 未配置", null, null);
        }
        String base = properties.endpoint().toString().replaceAll("/+$", "");
        return URI.create(base + (path.startsWith("/") ? path : "/" + path));
    }

    private Duration timeout(ArcadeDbTimeoutKind kind) {
        return switch (kind) {
            case QUERY -> properties.queryTimeout();
            case BATCH_WRITE -> properties.batchWriteTimeout();
            case ALGORITHM -> properties.algorithmTimeout();
        };
    }

    private static Map<String, Object> commandBody(String statement, Map<String, Object> parameters) {
        if (statement == null || statement.isBlank()) {
            throw new IllegalArgumentException("ArcadeDB statement 不能为空");
        }
        return Map.of("language", "sql", "command", statement,
                "params", parameters == null ? Map.of()
                        : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parameters)));
    }

    private String json(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ArcadeDbClientException(ArcadeDbFailureCategory.CONFIGURATION,
                    "ArcadeDB 请求参数无法序列化", null, e);
        }
    }

    private static String pathSegment(String value) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException("ArcadeDB 路径参数无效");
        }
        return value;
    }

    private static String truncate(String value) {
        if (value == null) return "-";
        return value.length() <= 500 ? value : value.substring(0, 500) + "…[truncated]";
    }
}
