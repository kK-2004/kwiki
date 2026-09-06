package com.kwiki.rag.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

import java.util.Map;

/**
 * Persists sanitized routing/rewrite trace metadata (request_trace table) for
 * audit: source, rule version, intent, rewrite mode, subquery count, latency,
 * and fallback reason. Credentials and unrestricted evidence never enter traces.
 */
@Repository
public class RoutingTraceStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcOperations jdbc;

    public RoutingTraceStore(ObjectProvider<JdbcOperations> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    public void recordRoute(long userId, RetrievalPlan plan, long latencyMillis) {
        if (jdbc == null) {
            return;
        }
        try {
            String traceJson = MAPPER.writeValueAsString(Map.of(
                    "stage", "route",
                    "source", plan.source().name(),
                    "ruleVersion", plan.ruleVersion() == null ? "" : plan.ruleVersion(),
                    "intent", plan.intent().name(),
                    "rewriteMode", plan.rewriteMode().name(),
                    "subqueryCount", plan.subqueries().size(),
                    "confidence", plan.confidence(),
                    "fallbackReason", plan.fallbackReason() == null ? "" : plan.fallbackReason(),
                    "latencyMillis", latencyMillis));
            jdbc.update("INSERT INTO request_trace (correlation_id, user_id, kind, trace_json) "
                            + "VALUES (?, ?, 'ROUTE', ?) "
                            + "ON DUPLICATE KEY UPDATE trace_json = VALUES(trace_json)",
                    MDC.get("traceId") == null ? "no-trace-id" : MDC.get("traceId"),
                    userId, traceJson);
        } catch (Exception e) {
            // trace persistence must never break request handling
        }
    }
}
