package com.kwiki.infrastructure.config;

import com.kk2004.common.web.RequestContextFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.filter.OrderedRequestContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Boot 3.5 compatibility for the kk-common SDK's web auto-configuration.
 *
 * <p>Spring Boot 3.5's {@code WebMvcAutoConfigurationAdapter} registers spring-web's
 * {@code org.springframework.web.filter.RequestContextFilter} under the bean name
 * {@code requestContextFilter}; its type-based condition cannot see the SDK's
 * differently-typed filter, so both definitions collide and startup fails. Declaring
 * spring-web's filter ourselves (exactly what Boot would have created) makes Boot's
 * conditional bean back off and leaves the shared name to the SDK filter.
 *
 * <p>The SDK filter is additionally pinned to run before kwiki's
 * {@code TraceIdResponseHeaderFilter} (order -200): the SDK echoes its own
 * request-context id as {@code X-Trace-Id}, which would otherwise overwrite the
 * W3C-correlated Brave trace id the echo filter must expose.
 *
 * <p>Remove both shims once the SDK resolves the Boot 3.5 name collision natively.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonSdkWebCompatibilityConfiguration {

    /**
     * Mirrors the SDK switch (web defaults on): without the SDK filter bean there is
     * nothing to register and nothing to disambiguate.
     */
    @Bean
    @ConditionalOnProperty(prefix = "kk.common.web", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    org.springframework.web.filter.RequestContextFilter kwikiSpringRequestContextFilter() {
        return new OrderedRequestContextFilter();
    }

    @Bean
    @ConditionalOnProperty(prefix = "kk.common.web", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<RequestContextFilter> kkRequestContextFilterRegistration(
            RequestContextFilter sdkRequestContextFilter) {
        FilterRegistrationBean<RequestContextFilter> registration =
                new FilterRegistrationBean<>(sdkRequestContextFilter);
        registration.setOrder(-210);
        return registration;
    }
}
