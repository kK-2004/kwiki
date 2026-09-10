package com.kwiki;

import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 最小化的上下文冒烟测试：应用上下文必须在没有任何
 * 外部中间件连接的情况下启动。已排除中间件自动配置，以便
 * 默认构建保持无 Docker、无连接状态。
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
