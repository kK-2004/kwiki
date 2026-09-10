package com.kwiki.infrastructure.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 在响应上暴露由 Micrometer 管理的链路追踪 ID（trace id）。追踪身份本身由
 * Micrometer Tracing（Brave）拥有：位于 HIGHEST_PRECEDENCE+1 运行的
 * 服务端观测过滤器会提取传入的 W3C {@code traceparent} 或启动一条新
 * 追踪，并将 traceId/spanId 放入 MDC 以便日志关联；基于 Spring Boot
 * 自动配置的构建器发出的 {@code RestClient}/{@code WebClient} 调用会
 * 自动注入 {@code traceparent} 头。本过滤器仅将当前链路追踪 ID 回显为
 * {@code X-Trace-Id}，以便 API 调用方可将响应与支持查询相关联。它在安全链之前运行，
 * 因此认证失败仍能携带链路追踪 ID。
 */
@Component
@Order(-200)
public class TraceIdResponseHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    private final Tracer tracer;

    public TraceIdResponseHeaderFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null && currentSpan.context() != null) {
            String traceId = currentSpan.context().traceId();
            if (traceId != null && !traceId.isBlank()) {
                response.setHeader(HEADER, traceId);
            }
        }
        filterChain.doFilter(request, response);
    }
}
