package com.kwiki.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * RFC 9728 受保护资源元数据：/mcp 的发现入口。MCP 客户端从 401 的
 * resource_metadata 指引或 well-known 路径读取 authorization_servers，
 * 进而自动完成客户端注册与授权码+PKCE 授权。端点匿名可读；
 * URL 一律来自可信配置的 kwiki.mcp.public-base-url，与请求头无关。
 */
@RestController
@ConditionalOnProperty(prefix = "kwiki.mcp", name = "enabled", havingValue = "true")
public class McpProtectedResourceMetadataController {

    private final McpProperties properties;

    public McpProtectedResourceMetadataController(McpProperties properties) {
        this.properties = properties;
    }

    @GetMapping({"/.well-known/oauth-protected-resource",
            "/.well-known/oauth-protected-resource/mcp"})
    public ResponseEntity<Map<String, Object>> metadata() {
        return ResponseEntity.ok(Map.of(
                "resource", properties.resourceIdentifier(),
                "authorization_servers", List.of(properties.issuer()),
                "scopes_supported", List.of(McpActor.SEARCH_SCOPE),
                "bearer_methods_supported", List.of("header")));
    }
}
