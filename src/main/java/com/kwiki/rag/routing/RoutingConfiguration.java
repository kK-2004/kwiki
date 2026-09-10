package com.kwiki.rag.routing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 将带版本号的默认关键词规则作为路由规则集 bean 暴露出去。 */
@Configuration
public class RoutingConfiguration {

    @Bean
    KeywordRuleSet defaultKeywordRuleSet() {
        return KeywordRuleSet.defaults();
    }
}
