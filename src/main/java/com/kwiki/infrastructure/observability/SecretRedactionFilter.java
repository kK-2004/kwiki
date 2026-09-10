package com.kwiki.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 包装每个请求，使查询字符串的下游消费者（请求日志、
 * 错误上报、链路追踪）只能看到经过脱敏（redaction）的凭据，例如签名下载链接的
 * 预签名（presigned）参数。结合 %redactedMsg logback 转换器（converter），
 * 可将认证头、API key、app token、密码与签名排除在捕获的日志之外。
 */
@Component
@Order(-190)
public class SecretRedactionFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpServletRequest safeRequest = new SanitizedQueryStringRequest(request);
        filterChain.doFilter(safeRequest, response);
    }

    private static final class SanitizedQueryStringRequest extends HttpServletRequestWrapper {

        private SanitizedQueryStringRequest(HttpServletRequest delegate) {
            super(delegate);
        }

        @Override
        public String getQueryString() {
            return SecretRedaction.redactQueryString(super.getQueryString());
        }
    }
}
