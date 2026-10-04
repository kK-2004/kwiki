package com.kwiki.graph;

import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 开启图功能时的上下文冒烟测试：图构建执行器、各阶段、插件、ArcadeDB 与模型客户端
 * 的装配必须能在无外部连接的情况下完成。默认上下文测试关闭了图功能，覆盖不到这些 Bean。
 */
@SpringBootTest
@org.springframework.context.annotation.Import(com.kwiki.testutil.WikiMockBeans.class)
class GraphEnabledContextTest {

    @org.springframework.test.context.DynamicPropertySource
    static void registerProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        StandardTestProperties.register(registry);
        registry.add("kwiki.graph.enabled", () -> "true");
        // 图功能开启时配置守卫要求 ArcadeDB 连接信息；测试中不会真正连接
        registry.add("kwiki.external.arcadedb.endpoint", () -> "http://arcadedb.test.invalid:2480");
        registry.add("kwiki.external.arcadedb.database", () -> "kwiki_test");
        registry.add("kwiki.external.arcadedb.token", () -> "test-token");
        // 执行器在测试期间不领取任务
        registry.add("kwiki.graph.build.initial-delay", () -> "1h");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    void contextLoadsWithGraphEnabled() {
        // 这些组件曾因 @ConditionalOnBean 在组件扫描阶段判断失败而被静默跳过，必须真实存在
        for (Class<?> required : java.util.List.of(
                com.kwiki.graph.build.GraphBuildExecutor.class,
                com.kwiki.infrastructure.mysql.JdbcGraphEntityRegistry.class,
                com.kwiki.infrastructure.arcadedb.ArcadeDbNativeCommunityDetection.class,
                com.kwiki.infrastructure.arcadedb.ArcadeDbGraphExpansionAdapter.class,
                com.kwiki.infrastructure.arcadedb.ArcadeDbScratchProjectionSpace.class,
                com.kwiki.infrastructure.elasticsearch.EsGraphSourceChildResolver.class,
                com.kwiki.graph.plugin.LlmGraphExtractorV1.class,
                com.kwiki.graph.plugin.LlmCommunitySummarizerV1.class,
                com.kwiki.graph.plugin.ArcadeDbLeidenV1.class)) {
            org.assertj.core.api.Assertions.assertThat(context.getBeanNamesForType(required))
                    .as("缺少 Bean：" + required.getSimpleName()).isNotEmpty();
        }
    }
}
