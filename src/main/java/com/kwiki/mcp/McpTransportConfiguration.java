package com.kwiki.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 传输与工具装配。提供自定义的 Streamable HTTP transport provider
 * （自动配置的 @ConditionalOnMissingBean 会退避）：contextExtractor 在
 * 已通过资源服务器链认证的 HTTP 边界把 {@link McpActor} 固化进
 * transport 上下文，工具处理器再从 exchange 取出——不依赖
 * ThreadLocal 与工具执行线程一致，也不接受任何未经验证的身份。
 *
 * <p>每个工具在执行前独立校验 actor 与入口 scope；tools/call 不因
 * tools/list 的可见性而获得豁免。业务拒绝以结构化错误码返回，
 * 不携带内部堆栈。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "kwiki.mcp", name = "enabled", havingValue = "true")
public class McpTransportConfiguration {

    @Bean
    public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            @Qualifier("mcpServerObjectMapper") ObjectMapper mcpObjectMapper,
            McpServerStreamableHttpProperties streamableHttpProperties,
            McpTokenIntrospector introspector) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mcpObjectMapper))
                .mcpEndpoint(streamableHttpProperties.getMcpEndpoint())
                // 工具可能在任意线程执行，ThreadLocal 的 SecurityContext 不可依赖。
                // 提取器在 HTTP 边界用与资源服务器链同一个内省器重新校验 Bearer
                // 令牌并固化不可变 actor；校验失败即无身份，工具侧拒绝。
                .contextExtractor(request -> {
                    String authorization = request.headers().asHttpHeaders().getFirst("Authorization");
                    if (authorization == null || !authorization.startsWith("Bearer ")) {
                        return McpTransportContext.EMPTY;
                    }
                    try {
                        McpActor actor = introspector
                                .introspect(authorization.substring(7).trim())
                                .actor();
                        return McpTransportContext.create(
                                Map.of(McpActor.TRANSPORT_CONTEXT_KEY, actor));
                    } catch (RuntimeException denied) {
                        return McpTransportContext.EMPTY;
                    }
                })
                .build();
    }

    @Bean
    @org.springframework.context.annotation.Primary
    public McpSyncServerCustomizer kwikiMcpTools(McpSearchTools tools) {
        return specification -> specification.tools(List.of(
                listKnowledgeBasesSpecification(tools),
                searchSpecification(tools)));
    }

    private McpServerFeatures.SyncToolSpecification listKnowledgeBasesSpecification(
            McpSearchTools tools) {
        var tool = new io.modelcontextprotocol.spec.McpSchema.Tool.Builder()
                .name("list_knowledge_bases")
                .title("可检索知识库目录")
                .description("列出当前用户可检索的知识库目录（kbId、名称、描述）。"
                        + "用户要求浏览知识库，或指定库名但需要解析 kbId 时调用；普通问题直接调用 search，无需先查目录。"
                        + "最多返回 200 个知识库；truncated=true 表示目录不完整，不能据此判定某库不存在。只读，无副作用。")
                .inputSchema(new io.modelcontextprotocol.spec.McpSchema.JsonSchema(
                        "object", Map.of(), List.of(), false, null, null))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) ->
                        call(exchange, "list_knowledge_bases", actor -> tools.listKnowledgeBases(actor)))
                .build();
    }

    private McpServerFeatures.SyncToolSpecification searchSpecification(McpSearchTools tools) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "检索问题或关键词，不超过 512 字符"));
        properties.put("kb_ids", Map.of(
                "type", "array",
                "items", Map.of("type", "integer"),
                "description", "可选：限定检索的知识库 id 列表（来自 list_knowledge_bases）；"
                        + "缺省时检索 token 有权限的全部知识库。越权 id 会被整体拒绝。"));
        properties.put("strategy", Map.of(
                "type", "string",
                "enum", List.of("hybrid", "bm25", "vector"),
                "description", "可选：召回策略，默认 hybrid（BM25+向量，RRF 融合）"));
        properties.put("top_k", Map.of(
                "type", "integer",
                "description", "可选：每个召回分支的条数，默认 10，上限为服务端预算"));
        properties.put("parent_limit", Map.of(
                "type", "integer",
                "description", "可选：返回的父分块（上下文块）数量上限，默认为服务端预算"));
        var tool = new io.modelcontextprotocol.spec.McpSchema.Tool.Builder()
                .name("search")
                .title("知识库检索")
                .description("检索 kwiki 知识库中的问题或关键词，返回相关证据块及来源信息，供引用作答。"
                        + "普通问题直接传 query，默认 hybrid；省略 kb_ids 时搜索当前用户有权限的全部知识库，无需先列目录。"
                        + "仅在需要限定知识库时传已确认的 kb_ids，不猜测 ID。证据块不代表完整文档，空结果不代表资料不存在。"
                        + "证据不足时围绕缺失信息补查，足够时停止检索。只读，无副作用。")
                .inputSchema(new io.modelcontextprotocol.spec.McpSchema.JsonSchema(
                        "object", properties, List.of("query"), false, null, null))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> call(exchange, "search", actor -> {
                    Map<String, Object> arguments =
                            request.arguments() == null ? Map.of() : request.arguments();
                    return tools.search(
                            actor,
                            str(arguments.get("query")),
                            longList(arguments.get("kb_ids")),
                            str(arguments.get("strategy")),
                            intOrNull(arguments.get("top_k")),
                            intOrNull(arguments.get("parent_limit")));
                }))
                .build();
    }

    private interface ToolAction {
        Map<String, Object> execute(McpActor actor) throws Exception;
    }

    /** 统一的工具执行边界：身份必验、scope 必查、错误收敛为 isError 结果。 */
    private io.modelcontextprotocol.spec.McpSchema.CallToolResult call(
            McpSyncServerExchange exchange, String toolName, ToolAction action) {
        McpTransportContext context = exchange.transportContext();
        McpActor actor = context == null
                ? null
                : (McpActor) context.get(McpActor.TRANSPORT_CONTEXT_KEY);
        if (actor == null) {
            return errorResult("unauthenticated",
                    "the request was not authenticated; obtain a token via OAuth first");
        }
        if (!actor.hasSearchScope()) {
            return errorResult("insufficient_scope",
                    "token does not carry scope " + McpActor.SEARCH_SCOPE);
        }
        try {
            Map<String, Object> result = action.execute(actor);
            return io.modelcontextprotocol.spec.McpSchema.CallToolResult.builder()
                    .structuredContent(result)
                    .addTextContent(toJson(result))
                    .build();
        } catch (McpSearchTools.McpToolException e) {
            return errorResult(e.code(), e.getMessage());
        } catch (Exception e) {
            return errorResult("retrieval_failed",
                    "the search service could not complete the request");
        }
    }

    private io.modelcontextprotocol.spec.McpSchema.CallToolResult errorResult(String code,
                                                                              String message) {
        Map<String, Object> error = Map.of("error", code, "message", message);
        return io.modelcontextprotocol.spec.McpSchema.CallToolResult.builder()
                .isError(true)
                .structuredContent(error)
                .addTextContent(toJson(error))
                .build();
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static List<Long> longList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> item instanceof Number number ? number.longValue()
                        : Long.parseLong(String.valueOf(item)))
                .toList();
    }
}
