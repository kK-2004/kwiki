package com.kwiki.graph.plugin;

import com.kwiki.graph.model.GraphModelClients;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 图功能开启时，大模型插件必须能由 Spring 按构造器注入创建（多构造器时需显式标注注入入口）。 */
class GraphPluginBeanCreationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("kwiki.graph.enabled=true")
            .withBean(GraphModelClients.class, () -> mock(GraphModelClients.class))
            .withUserConfiguration(LlmGraphExtractorV1.class, LlmCommunitySummarizerV1.class);

    @Test
    void 开启图功能时大模型插件可以被创建() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LlmGraphExtractorV1.class);
            assertThat(context).hasSingleBean(LlmCommunitySummarizerV1.class);
        });
    }
}
