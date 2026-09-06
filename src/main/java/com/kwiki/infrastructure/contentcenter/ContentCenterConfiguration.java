package com.kwiki.infrastructure.contentcenter;

import com.kk.sdk.ContentCenterClient;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Content center (k-File) adapter wiring. Builds one reusable SDK client from the
 * validated kwiki.content-center properties; the app token never leaves runtime
 * configuration and is never logged here. SDK 0.1.3 exposes no health operation,
 * so startup relies on binding-time validation only.
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
