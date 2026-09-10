package com.kwiki.infrastructure.contentcenter;

import com.kk.sdk.ContentCenterClient;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内容中心（k-File）适配器（adapter）装配。从已校验的
 * kwiki.content-center 属性构建一个可复用的 SDK 客户端；app token 绝不会离开
 * 运行时配置，也绝不会在此处记录日志。SDK 0.1.3 未暴露健康检查操作，
 * 因此启动仅依赖绑定阶段的校验。
 */
@Configuration
public class ContentCenterConfiguration {

    @Bean
    ContentCenterClient kwikiContentCenterClient(ExternalServicesProperties properties) {
        ExternalServicesProperties.ContentCenter config = properties.contentCenter();
        return ContentCenterClient.builder()
                .baseUrl(config.baseUrl())
                .appToken(config.appToken())
                .connectTimeout(config.connectTimeout())
                .requestTimeout(config.requestTimeout())
                .build();
    }
}
