package com.kwiki.rag.routing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes the versioned default keyword rules as the routing rule set bean. */
@Configuration
public class RoutingConfiguration {

    @Bean
    KeywordRuleSet defaultKeywordRuleSet() {
        return KeywordRuleSet.defaults();
    }
}
