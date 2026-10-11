package com.kwiki.rag.tool;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.kwiki.rag.orchestration.RunFailure;
import com.kwiki.rag.retrieval.*;
import com.kwiki.rag.routing.QueryNormalizer;
import com.networknt.schema.*;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public final class ToolRegistry {
    public static final ObjectMapper JSON =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(20)
                                                    .maxStringLength(32000)
                                                    .build())
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record ToolDefinition(
            String name,
            String version,
            String description,
            String inputSchema,
            String outputSchema) {}

    public record ValidatedCall(ToolCall call, SearchArguments arguments) {}

    public record ValidatedAgentCall(ToolCall call, AgentToolArguments arguments) {}

    private final SchemaRegistry schemas =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Map<String, Schema> compiled = new HashMap<>();

    @FunctionalInterface
    public interface ToolHandler {
        HybridRetrievalOrchestrator.RetrievalOutcome execute(
                SearchArguments arguments,
                com.kwiki.rag.orchestration.RunContext run,
                HybridRetrievalOrchestrator.Accumulation accumulated);
    }

    private final Map<String, ToolHandler> handlers =
            new java.util.concurrent.ConcurrentHashMap<>();

    public void registerHandler(String name, ToolHandler handler) {
        get(name);
        if (handlers.putIfAbsent(name, handler) != null)
            throw new IllegalStateException("duplicate tool handler");
    }

    public ToolHandler handler(String name) {
        get(name);
        var handler = handlers.get(name);
        if (handler == null) throw new RunFailure("tool-unavailable");
        return handler;
    }

    private static final String ES_SEARCH = "es_search";

    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();

    public ToolRegistry() {
        definitions.put(
                ES_SEARCH,
                new ToolDefinition(
                        ES_SEARCH,
                        "1",
                        "Search authorized Wiki CHILD chunks using BM25, VECTOR or HYBRID. No scope"
                            + " or DSL parameters.",
                        resource("es-search-v1"),
                        resource("tool-result-v1")));
        definitions.put(AgentTools.SEARCH_CHUNKS, agentTool(AgentTools.SEARCH_CHUNKS, "search-chunks-v1",
                "Search authorized Wiki CHILD chunks with BM25 (exact identifiers), VECTOR (semantic) or"
                        + " HYBRID (coverage) and add the hits to the evidence pool. range BASE is the normal"
                        + " depth; EXPANDED searches wider and keeps more hits. Up to three distinct queries"
                        + " per call. No scope or DSL parameters."));
        definitions.put(AgentTools.EXPAND_PARENTS, agentTool(AgentTools.EXPAND_PARENTS, "expand-parents-v1",
                "Fetch the full PARENT sections of chunks already in the evidence pool when the snippets lack"
                        + " context. Optionally restrict to specific childChunkKeys taken from the pool;"
                        + " omit it to use every chunk in the pool."));
        definitions.put(AgentTools.GRAPH_EXPAND, agentTool(AgentTools.GRAPH_EXPAND, "graph-expand-v1",
                "Expand the evidence pool through the knowledge graph (entities and communities) starting"
                        + " from the chunks already found. Useful for entity and relationship questions."
                        + " Optionally give a query; the current retrieval query is used by default."));
        definitions.put(AgentTools.REWRITE_QUERY, agentTool(AgentTools.REWRITE_QUERY, "rewrite-query-v1",
                "Rewrite the current retrieval query. Modes: CONVERSATIONAL (resolve references with the chat"
                        + " history), EXPANSION (add domain terms to a short query), DECOMPOSITION (split a"
                        + " compound question into sub-queries), FEEDBACK (redesign the query using earlier"
                        + " failures). The outcome is shown in the next observation."));
        definitions.put(AgentTools.ASK_USER, agentTool(AgentTools.ASK_USER, "ask-user-v1",
                "Ask the user a clarifying question when the evidence shows several plausible interpretations"
                        + " (for example hits from different knowledge bases or pages) or a key constraint is"
                        + " missing. Offer up to four options; each refers to a childChunkKey from the evidence"
                        + " pool (ref) with a short label, the server derives the knowledge base and page from the"
                        + " evidence. It must be the only tool call of the turn and ends this run; the answer of"
                        + " the user starts a new run."));
        for (String name :
                List.of(
                        "es-search-v1",
                        "tool-result-v1",
                        "tool-result-v2",
                        "search-chunks-v1",
                        "expand-parents-v1",
                        "graph-expand-v1",
                        "rewrite-query-v1",
                        "ask-user-v1",
                        "quality-v1",
                        "quality-v2"))
            compiled.put(name, schemas.getSchema(parse(resource(name))));
    }

    private static ToolDefinition agentTool(String name, String inputSchema, String description) {
        return new ToolDefinition(name, "1", description, resource(inputSchema), resource("tool-result-v2"));
    }

    private static String resource(String name) {
        try (var in =
                ToolRegistry.class.getResourceAsStream("/agentic/tools/" + name + ".schema.json")) {
            if (in == null) throw new IllegalStateException("missing tool schema");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("unreadable tool schema");
        }
    }

    public ToolDefinition get(String name) {
        ToolDefinition definition = name == null ? null : definitions.get(name);
        if (definition == null) throw new RunFailure("unknown-tool");
        return definition;
    }

    public String schema(String name) {
        return resource(name);
    }

    public static JsonNode parse(String raw) {
        if (raw == null || raw.length() > 32000) throw new RunFailure("invalid-json");
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new RunFailure("invalid-json");
        }
    }

    public JsonNode validate(String schema, String raw) {
        JsonNode node = parse(raw);
        if (node == null || !compiled.get(schema).validate(node).isEmpty())
            throw new RunFailure("invalid-" + schema);
        return node;
    }

    public List<ValidatedCall> validateBatch(
            List<ToolCall> calls, RetrievalBudgets budgets, int maximum) {
        if (calls == null || calls.isEmpty() || calls.size() > maximum)
            throw new RunFailure("invalid-tool-count");
        Set<String> ids = new HashSet<>(), allQueries = new HashSet<>();
        var validated = new ArrayList<ValidatedCall>();
        for (var call : calls) {
            get(call.name());
            if (!ES_SEARCH.equals(call.name())) throw new RunFailure("unknown-tool");
            if (call.callId() == null
                    || call.callId().isBlank()
                    || call.callId().length() > 200
                    || !ids.add(call.callId())) throw new RunFailure("invalid-call-id");
            var node = validate("es-search-v1", call.rawArguments());
            List<String> queries = new ArrayList<>();
            node.get("queries").forEach(q -> queries.add(QueryNormalizer.normalize(q.asText())));
            if (queries.stream().anyMatch(String::isBlank)
                    || queries.stream().distinct().count() != queries.size())
                throw new RunFailure("invalid-queries");
            allQueries.addAll(queries);
            int topK = node.get("topK").intValue();
            if (topK > budgets.childBranchTopK || allQueries.size() > budgets.maxSubqueries)
                throw new RunFailure("retrieval-budget-exceeded");
            validated.add(
                    new ValidatedCall(
                            call,
                            new SearchArguments(
                                    queries,
                                    RetrievalStrategy.valueOf(node.get("strategy").asText()),
                                    topK)));
        }
        return List.copyOf(validated);
    }

    /** 本轮向 planner 暴露的 agent 工具（不含旧的 es_search）。 */
    public List<ToolDefinition> available(ToolAvailability availability) {
        List<ToolDefinition> tools = new ArrayList<>();
        tools.add(definitions.get(AgentTools.SEARCH_CHUNKS));
        if (availability.hasChildren()) tools.add(definitions.get(AgentTools.EXPAND_PARENTS));
        if (availability.graphEnabled() && availability.hasChildren())
            tools.add(definitions.get(AgentTools.GRAPH_EXPAND));
        if (availability.canRewrite()) tools.add(definitions.get(AgentTools.REWRITE_QUERY));
        if (availability.canAsk() && availability.hasChildren())
            tools.add(definitions.get(AgentTools.ASK_USER));
        return List.copyOf(tools);
    }

    /**
     * 校验 planner 产出的一批 agent 工具调用：数量、callId、工具是否存在且当前可用、
     * 参数 schema，以及批级规则（去重后的查询总数、每批至多一次改写）。
     */
    public List<ValidatedAgentCall> validateAgentBatch(
            List<ToolCall> calls, ToolAvailability availability, int maxCalls, int maxQueries) {
        if (calls == null || calls.isEmpty() || calls.size() > maxCalls)
            throw new RunFailure("invalid-tool-count");
        if (calls.size() > 1 && calls.stream().anyMatch(c -> AgentTools.ASK_USER.equals(c.name())))
            throw new RunFailure("ask-user-must-be-alone");
        Set<String> exposed = new HashSet<>();
        available(availability).forEach(definition -> exposed.add(definition.name()));
        Set<String> ids = new HashSet<>(), allQueries = new HashSet<>();
        int rewrites = 0;
        var validated = new ArrayList<ValidatedAgentCall>();
        for (var call : calls) {
            if (call.callId() == null
                    || call.callId().isBlank()
                    || call.callId().length() > 200
                    || !ids.add(call.callId())) throw new RunFailure("invalid-call-id");
            if (!AgentTools.ALL.contains(call.name())) throw new RunFailure("unknown-tool");
            if (!exposed.contains(call.name())) throw new RunFailure("tool-unavailable");
            var node = validate(call.name().replace('_', '-') + "-v1", call.rawArguments());
            if (AgentTools.REWRITE_QUERY.equals(call.name()) && ++rewrites > 1)
                throw new RunFailure("duplicate-rewrite");
            AgentToolArguments arguments = parseAgentArguments(call.name(), node, allQueries);
            if (allQueries.size() > maxQueries) throw new RunFailure("retrieval-budget-exceeded");
            validated.add(new ValidatedAgentCall(call, arguments));
        }
        return List.copyOf(validated);
    }

    private static AgentToolArguments parseAgentArguments(
            String name, JsonNode node, Set<String> allQueries) {
        return switch (name) {
            case AgentTools.SEARCH_CHUNKS -> {
                List<String> queries = new ArrayList<>();
                node.get("queries").forEach(q -> queries.add(QueryNormalizer.normalize(q.asText())));
                if (queries.stream().anyMatch(String::isBlank)
                        || queries.stream().distinct().count() != queries.size())
                    throw new RunFailure("invalid-queries");
                allQueries.addAll(queries);
                yield new AgentToolArguments.SearchChunks(
                        queries,
                        RetrievalStrategy.valueOf(node.get("strategy").asText()),
                        AgentToolArguments.Range.valueOf(node.get("range").asText()));
            }
            case AgentTools.EXPAND_PARENTS -> {
                List<String> keys = new ArrayList<>();
                if (node.has("childChunkKeys")) node.get("childChunkKeys").forEach(k -> keys.add(k.asText()));
                yield new AgentToolArguments.ExpandParents(
                        keys,
                        node.has("range")
                                ? AgentToolArguments.Range.valueOf(node.get("range").asText())
                                : AgentToolArguments.Range.BASE);
            }
            case AgentTools.GRAPH_EXPAND -> {
                String query = node.has("query") ? QueryNormalizer.normalize(node.get("query").asText()) : null;
                if (query != null && query.isBlank()) throw new RunFailure("invalid-queries");
                yield new AgentToolArguments.GraphExpand(query);
            }
            case AgentTools.REWRITE_QUERY -> new AgentToolArguments.RewriteQuery(
                    AgentToolArguments.RewriteKind.valueOf(node.get("mode").asText()));
            case AgentTools.ASK_USER -> {
                List<AgentToolArguments.AskUser.Option> options = new ArrayList<>();
                if (node.has("options"))
                    node.get("options").forEach(o -> options.add(new AgentToolArguments.AskUser.Option(
                            o.get("ref").asText(), o.get("label").asText().strip())));
                yield new AgentToolArguments.AskUser(
                        node.get("question").asText().strip(),
                        options,
                        !node.has("allowFreeText") || node.get("allowFreeText").asBoolean());
            }
            default -> throw new RunFailure("unknown-tool");
        };
    }

    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new RunFailure("serialization-failed");
        }
    }
}
