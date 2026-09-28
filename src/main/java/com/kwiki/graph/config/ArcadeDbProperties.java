package com.kwiki.graph.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * 独立 ArcadeDB 服务的连接 token、超时和受控临时库配置。
 * 认证使用单一 token（该 token 需同时具备在线读与建库/临时库权限），
 * 不再细分读/构建两组凭据；token 仅从环境/密钥配置读取，不通过后台回显。
 */
@ConfigurationProperties(prefix = "kwiki.external.arcadedb")
@Validated
public record ArcadeDbProperties(
        URI endpoint,
        String database,
        String token,
        @NotNull @DefaultValue("3s") Duration connectTimeout,
        @NotNull @DefaultValue("1500ms") Duration queryTimeout,
        @NotNull @DefaultValue("30s") Duration batchWriteTimeout,
        @NotNull @DefaultValue("15m") Duration algorithmTimeout,
        @NotNull @Min(1) @Max(128) @DefaultValue("8") Integer maxConnections,
        @Valid @NotNull @DefaultValue Tls tls,
        @NotNull @DefaultValue("kwiki_leiden_") String temporaryDatabasePrefix,
        String requiredServerVersion) {

    public record Tls(
            @NotNull @DefaultValue("false") Boolean enabled,
            @NotNull @DefaultValue("true") Boolean verifyCertificate) {
    }

    /**
     * 图功能关闭时允许外部服务段为空；开启时则一次性校验完整连接目标与
     * 访问 token。校验消息只指出配置字段，不回显 token。
     */
    public void requireUsableWhenEnabled(GraphProperties graph) {
        if (graph == null || !graph.isEnabled()) {
            return;
        }
        if (endpoint == null || endpoint.getScheme() == null
                || !("http".equalsIgnoreCase(endpoint.getScheme())
                || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw new IllegalStateException("kwiki.external.arcadedb.endpoint 必须是 http(s) 地址");
        }
        requireText(database, "kwiki.external.arcadedb.database");
        requireText(token, "kwiki.external.arcadedb.token");
        if (temporaryDatabasePrefix == null || temporaryDatabasePrefix.isBlank()) {
            throw new IllegalStateException("kwiki.external.arcadedb.temporary-database-prefix 不能为空");
        }
        // 锁定版本号可选：未填写时跳过版本比对（启动期由 GraphConfigurationGuard 打告警），
        // 填写后健康探测按前缀比对实际版本，不一致即判定图构建不可用。
    }

    /** 是否锁定了兼容的 ArcadeDB 服务版本；未锁定时能力探测跳过版本比对。 */
    public boolean serverVersionPinned() {
        return requiredServerVersion != null && !requiredServerVersion.isBlank();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " 不能为空");
        }
    }
}
