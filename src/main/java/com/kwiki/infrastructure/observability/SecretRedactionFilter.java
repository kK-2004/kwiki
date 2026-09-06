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
 * Wraps every request so downstream consumers of the query string (request logging,
 * error reporting, traces) only ever see redacted credentials, e.g. signed download-link
 * presigned parameters. Combined with the %redactedMsg logback converter this keeps
 * authorization headers, API keys, app tokens, passwords, and signatures out of captured logs.
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
