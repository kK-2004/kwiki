package com.kwiki.rag.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

import java.util.Map;

/**
 * 持久化已净化的路由/改写追踪元数据（request_trace 表），用于
 * 审计：来源、规则版本、意图、改写模式、子查询数量、耗时，
 * 以及回退原因。凭据与未受限的证据绝不会进入追踪记录。
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
            // 追踪持久化绝不能中断请求处理
        }
    }
}
