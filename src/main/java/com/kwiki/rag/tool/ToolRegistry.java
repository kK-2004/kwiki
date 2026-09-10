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

    private final ToolDefinition search;

    public ToolRegistry() {
        search =
                new ToolDefinition(
                        "es_search",
                        "1",
                        "Search authorized Wiki CHILD chunks using BM25, VECTOR or HYBRID. No scope"
                            + " or DSL parameters.",
                        resource("es-search-v1"),
                        resource("tool-result-v1"));
        for (String name : List.of("es-search-v1", "tool-result-v1", "quality-v1", "quality-v2"))
            compiled.put(name, schemas.getSchema(parse(resource(name))));
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
        if (!search.name().equals(name)) throw new RunFailure("unknown-tool");
        return search;
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

    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new RunFailure("serialization-failed");
        }
    }
}
