package com.kwiki.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * MCP 接入配置。{@code enabled} 是总开关：同时控制
 * {@code spring.ai.mcp.server.enabled}（/mcp 端点）与本包的安全链、
 * 工具与授权服务器装配，默认关闭。
 *
 * <p>{@code publicBaseUrl} 是外部可达的基准 URL（无尾部斜杠）：issuer、
 * 受保护资源标识与发现元数据都由它派生，绝不从 Host 或
 * X-Forwarded-* 请求头推断。凭据不落在本配置中。</p>
 */
@ConfigurationProperties(prefix = "kwiki.mcp")
public record McpProperties(
        @DefaultValue("false") boolean enabled,
        String publicBaseUrl,
        @DefaultValue("30m") Duration accessTokenTtl,
        @DefaultValue("7d") Duration refreshTokenTtl,
        @DefaultValue("jdbc") String authStore,
        @DefaultValue("true") boolean dynamicClientRegistration) {

    public String resourceIdentifier() {
        return requireBaseUrl() + "/mcp";
    }

    public String issuer() {
        return requireBaseUrl();
    }

    /** 去掉尾部斜杠后的基准 URL；启用 MCP 时必填。 */
    public String requireBaseUrl() {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "kwiki.mcp.public-base-url is required when kwiki.mcp.enabled=true");
        }
        String trimmed = publicBaseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public boolean jdbcStore() {
        return !"memory".equalsIgnoreCase(authStore);
    }
}
