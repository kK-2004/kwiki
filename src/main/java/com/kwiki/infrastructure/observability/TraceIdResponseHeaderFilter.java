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
 * Surfaces the Micrometer-managed trace id on the response. Trace identity itself is
 * owned by Micrometer Tracing (Brave): the server observation filter, which runs at
 * HIGHEST_PRECEDENCE+1, extracts an incoming W3C {@code traceparent} or starts a new
 * trace, and puts traceId/spanId into the MDC for log correlation; outgoing
 * {@code RestClient}/{@code WebClient} calls built from Spring Boot's auto-configured
 * builders inject the {@code traceparent} header automatically. This filter only echoes
 * the current trace id as {@code X-Trace-Id} so API callers can correlate responses
 * with support queries. Runs before the security chain so authentication failures
 * still carry a trace id.
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
