package com.kwiki;

import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Minimal context smoke test: the application context must start without any
 * external middleware connection. Middleware auto-configuration is excluded so
 * the default build stays Docker-free and connection-free.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(com.kwiki.testutil.WikiMockBeans.class)
class KwikiApplicationTest {

    @org.springframework.test.context.DynamicPropertySource
    static void registerStandardTestProperties(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        StandardTestProperties.register(registry);
    }

    @Test
    void contextLoadsWithoutExternalConnections() {
    }
}
