package com.kwiki.infrastructure.config;

import com.kk2004.common.web.RequestContextFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.filter.OrderedRequestContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 适配 kk-common SDK 的 Web 自动配置以兼容 Boot 3.5。
 *
 * <p>Spring Boot 3.5 的 {@code WebMvcAutoConfigurationAdapter} 会以 bean 名称
 * {@code requestContextFilter} 注册 spring-web 的
 * {@code org.springframework.web.filter.RequestContextFilter}；其基于类型的
 * 条件无法识别 SDK 中类型不同的过滤器，导致两份定义冲突并使启动失败。由我们
 * 自行声明 spring-web 的过滤器（与 Boot 原本会创建的完全一致）可让 Boot 的
 * 条件化 bean 退让，将该共享名称留给 SDK 过滤器。
 *
 * <p>SDK 过滤器还被固定为在 kwiki 的
 * {@code TraceIdResponseHeaderFilter}（顺序 -200）之前运行：SDK 会将自身的
 * 请求上下文 id 回显为 {@code X-Trace-Id}，否则会覆盖掉回显过滤器必须暴露的
 * 与 W3C 关联的 Brave 链路追踪 ID。
 *
 * <p>待 SDK 原生解决 Boot 3.5 的命名冲突后，移除这两处兼容垫片。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonSdkWebCompatibilityConfiguration {

    /**
     * 与 SDK 开关保持一致（Web 默认开启）：若不存在 SDK 过滤器 bean，
     * 则没有需要注册或消歧的内容。
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
