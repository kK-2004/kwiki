package com.kwiki.indexing.config;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 绑定层面的契约：未提供环境变量时 application.yml 解析出保守
 * 默认限额/阈值/开关；KWIKI_INDEX_* 环境变量按占位符覆盖；
 * 清单与凭据档案按部署属性精确绑定。
 */
class IndexingPropertiesTest {

    private static PropertySources applicationYaml() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        return sources;
    }

    private static Binder binderFor(PropertySources sources) {
        return new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
    }

    @Test
    void yamlDefaultsAreConservativeWhenEnvironmentIsSilent() throws IOException {
        IndexingProperties props = binderFor(applicationYaml())
                .bind("kwiki.indexing", Bindable.of(IndexingProperties.class)).get();

        assertThat(props.manifests()).isEmpty();
        assertThat(props.embeddings()).isEmpty();
        assertThat(props.rebuild().batchSize()).isEqualTo(50);
        assertThat(props.rebuild().maxConcurrentRuns()).isEqualTo(2);
        assertThat(props.rebuild().embeddingQps()).isEqualTo(20);
        assertThat(props.catchup().batchSize()).isEqualTo(100);
        assertThat(props.catchup().scanInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.capacity().minStorageHeadroomPercent()).isEqualTo(20);
        assertThat(props.capacity().maxManagedIndices()).isEqualTo(8);
        assertThat(props.capacity().healthTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.management().mutationsEnabled()).isFalse();
    }

    @Test
    void environmentVariablesOverrideTheYamlPlaceholders() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("env-override", Map.of(
                "KWIKI_INDEX_REBUILD_BATCH_SIZE", "25",
                "KWIKI_INDEX_CATCHUP_SCAN_INTERVAL", "15s",
                "KWIKI_INDEX_MANAGEMENT_MUTATIONS_ENABLED", "true")));
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);

        IndexingProperties props = binderFor(sources)
                .bind("kwiki.indexing", Bindable.of(IndexingProperties.class)).get();

        assertThat(props.rebuild().batchSize()).isEqualTo(25);
        assertThat(props.catchup().scanInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(props.management().mutationsEnabled()).isTrue();
        // 未覆盖的键保持 yaml 默认
        assertThat(props.rebuild().embeddingQps()).isEqualTo(20);
    }

    @Test
    void manifestsAndEmbeddingProfilesBindFromRelaxedPropertyNames() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("kwiki.indexing.manifests[0].id", "gen-2048")
                .withProperty("kwiki.indexing.manifests[0].parser-version", "kwiki-parse-1")
                .withProperty("kwiki.indexing.manifests[0].chunker-version", "kwiki-chunk-1")
                .withProperty("kwiki.indexing.manifests[0].embedding-profile", "qwen-v4")
                .withProperty("kwiki.indexing.manifests[0].embedding-model", "text-embedding-v4")
                .withProperty("kwiki.indexing.manifests[0].dimensions", "2048")
                .withProperty("kwiki.indexing.manifests[0].mapping-schema-version", "2")
                .withProperty("kwiki.indexing.embeddings.qwen-v4.base-url", "https://embed.internal/v1")
                .withProperty("kwiki.indexing.embeddings.qwen-v4.api-key", "sk-secret")
                .withProperty("kwiki.indexing.embeddings.qwen-v4.request-timeout", "45s")
                .withProperty("kwiki.indexing.rebuild.embedding-qps", "80")
                .withProperty("kwiki.indexing.management.mutations-enabled", "true");

        IndexingProperties props = bind(environment.getPropertySources());

        assertThat(props.manifests()).hasSize(1);
        var manifest = props.manifests().get(0);
        assertThat(manifest.id()).isEqualTo("gen-2048");
        assertThat(manifest.embeddingProfile()).isEqualTo("qwen-v4");
        assertThat(manifest.dimensions()).isEqualTo(2048);
        assertThat(manifest.mappingSchemaVersion()).isEqualTo(2);

        var profile = props.embeddings().get("qwen-v4");
        assertThat(profile.baseUrl()).isEqualTo("https://embed.internal/v1");
        assertThat(profile.requestTimeout()).isEqualTo(Duration.ofSeconds(45));

        assertThat(props.rebuild().embeddingQps()).isEqualTo(80);
        assertThat(props.management().mutationsEnabled()).isTrue();
    }

    private static IndexingProperties bind(PropertySources sources) {
        return new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources))
                .bind("kwiki.indexing", Bindable.of(IndexingProperties.class)).get();
    }
}
