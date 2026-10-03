package com.kwiki.graph.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 图构建执行相关配置记录的默认值、绑定与 GraphProperties 的共存校验。 */
class GraphBuildConfigurationPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties({GraphBuildProperties.class, GraphClusteringProperties.class,
            GraphPluginDefaults.class, GraphModelProperties.class, GraphProperties.class})
    static class Config {
    }

    @Test
    void 未配置时使用全部默认值() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GraphBuildProperties.class);
            GraphBuildProperties build = context.getBean(GraphBuildProperties.class);
            assertThat(build.pollInterval()).isEqualTo(Duration.ofSeconds(10));
            assertThat(build.maxConcurrentRuns()).isEqualTo(2);
            assertThat(build.leaseDuration()).isEqualTo(Duration.ofMinutes(2));
            assertThat(build.maxAttempts()).isEqualTo(5);
            assertThat(build.retryBaseDelay()).isEqualTo(Duration.ofSeconds(1));
            assertThat(build.maxChunksPerRun()).isEqualTo(20000);
            assertThat(build.maxSummariesPerRun()).isEqualTo(5000);

            GraphClusteringProperties clustering = context.getBean(GraphClusteringProperties.class);
            assertThat(clustering.maxIterations()).isEqualTo(10);
            assertThat(clustering.resolution()).isEqualTo(1.0);

            GraphPluginDefaults defaults = context.getBean(GraphPluginDefaults.class);
            assertThat(defaults.extractor()).isEqualTo("kwiki-extractor-1");
            assertThat(defaults.communityDetector()).isEqualTo("arcadedb-leiden-1");
            assertThat(defaults.summarizer()).isEqualTo("kwiki-summarizer-1");

            GraphModelProperties models = context.getBean(GraphModelProperties.class);
            assertThat(models.extraction()).isNotNull();
            assertThat(models.summary()).isNotNull();
            assertThat(models.extraction().baseUrl()).isNull();
            assertThat(models.extraction().apiKey()).isNull();
            assertThat(models.extraction().model()).isNull();
            assertThat(models.extraction().requestTimeout()).isNull();
            assertThat(models.extraction().maxConcurrency()).isNull();
            assertThat(models.extraction().qps()).isNull();
            assertThat(models.extraction().maxRetries()).isNull();
        });
    }

    @Test
    void 子前缀按需绑定且与GraphProperties共存() {
        runner.withPropertyValues(
                        "kwiki.graph.enabled=true",
                        "kwiki.graph.build.poll-interval=3s",
                        "kwiki.graph.build.max-concurrent-runs=4",
                        "kwiki.graph.build.lease-duration=5m",
                        "kwiki.graph.build.max-attempts=3",
                        "kwiki.graph.build.retry-base-delay=2s",
                        "kwiki.graph.build.max-chunks-per-run=100",
                        "kwiki.graph.build.max-summaries-per-run=50",
                        "kwiki.graph.clustering.max-iterations=25",
                        "kwiki.graph.clustering.resolution=1.5",
                        "kwiki.graph.defaults.extractor=kwiki-extractor-1",
                        "kwiki.graph.models.extraction.model=x",
                        "kwiki.graph.models.extraction.base-url=https://llm.example/v1",
                        "kwiki.graph.models.extraction.api-key=k",
                        "kwiki.graph.models.extraction.request-timeout=30s",
                        "kwiki.graph.models.extraction.max-concurrency=8",
                        "kwiki.graph.models.extraction.qps=10.0",
                        "kwiki.graph.models.extraction.max-retries=4",
                        "kwiki.graph.models.summary.model=summary-model")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GraphBuildProperties build = context.getBean(GraphBuildProperties.class);
                    assertThat(build.pollInterval()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(build.maxConcurrentRuns()).isEqualTo(4);
                    assertThat(build.leaseDuration()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(build.maxAttempts()).isEqualTo(3);
                    assertThat(build.retryBaseDelay()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(build.maxChunksPerRun()).isEqualTo(100);
                    assertThat(build.maxSummariesPerRun()).isEqualTo(50);

                    assertThat(context.getBean(GraphClusteringProperties.class).maxIterations())
                            .isEqualTo(25);
                    assertThat(context.getBean(GraphClusteringProperties.class).resolution())
                            .isEqualTo(1.5);

                    assertThat(context.getBean(GraphPluginDefaults.class).extractor())
                            .isEqualTo("kwiki-extractor-1");

                    GraphModelProperties models = context.getBean(GraphModelProperties.class);
                    assertThat(models.extraction().model()).isEqualTo("x");
                    assertThat(models.extraction().baseUrl()).isEqualTo("https://llm.example/v1");
                    assertThat(models.extraction().apiKey()).isEqualTo("k");
                    assertThat(models.extraction().requestTimeout()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(models.extraction().maxConcurrency()).isEqualTo(8);
                    assertThat(models.extraction().qps()).isEqualTo(10.0);
                    assertThat(models.extraction().maxRetries()).isEqualTo(4);
                    assertThat(models.summary().model()).isEqualTo("summary-model");
                    assertThat(models.summary().baseUrl()).isNull();

                    // 同一上下文中 kwiki.graph 前缀的既有配置记录不受子前缀影响
                    assertThat(context.getBean(GraphProperties.class).isEnabled()).isTrue();
                });
    }

    @Test
    void 非法执行配置在绑定时被拒绝() {
        runner.withPropertyValues("kwiki.graph.build.max-concurrent-runs=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
