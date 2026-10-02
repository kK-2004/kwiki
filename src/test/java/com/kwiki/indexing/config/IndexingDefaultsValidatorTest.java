package com.kwiki.indexing.config;
import com.kwiki.indexing.parse.plugin.*;
import com.kwiki.indexing.schema.SchemaTestFixtures;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
class IndexingDefaultsValidatorTest {
    @Test void 默认绑定保留解析器偏好() {
        assertThat(new IndexingDefaultsProperties("kwiki-schema-1").parsers()).containsExactly("kwiki-parse-2", "kwiki-parse-1");
    }
    @Test void 空列表和未知解析器使启动失败() {
        for (List<String> ids : List.of(List.<String>of(), List.of("unknown-parser", "kwiki-parse-1"))) {
            assertThatThrownBy(() -> new IndexingDefaultsValidator(new IndexingDefaultsProperties("kwiki-schema-1", ids),
                    SchemaTestFixtures.schemas(), ParserTestFixtures.availableBoth(), chunkers())).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void 最后解析器必须可用而前项允许不可用() {
        var registry = ParserTestFixtures.unavailableTwo();
        new IndexingDefaultsValidator(new IndexingDefaultsProperties("kwiki-schema-1"), SchemaTestFixtures.schemas(), registry, chunkers());
        assertThatThrownBy(() -> new IndexingDefaultsValidator(new IndexingDefaultsProperties("kwiki-schema-1",
                List.of("kwiki-parse-1", "kwiki-parse-2")), SchemaTestFixtures.schemas(), registry, chunkers()))
                .hasMessageContaining("基线解析器不可用");
    }
    static com.kwiki.indexing.chunk.plugin.ChunkerRegistry chunkers() {
        var plugin = org.mockito.Mockito.mock(com.kwiki.indexing.chunk.plugin.ChunkerPlugin.class);
        org.mockito.Mockito.when(plugin.id()).thenReturn("kwiki-chunk-1");
        return new com.kwiki.indexing.chunk.plugin.ChunkerRegistry(List.of(plugin));
    }
    @Test void Spring实际绑定保留全部默认值并支持自定义分块器() {
        var binder = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of()));
        var defaults = binder.bindOrCreate("kwiki.indexing.defaults", IndexingDefaultsProperties.class);
        assertThat(defaults.indexSchema()).isEqualTo("kwiki-schema-1");
        assertThat(defaults.parsers()).containsExactly("kwiki-parse-2", "kwiki-parse-1");
        assertThat(defaults.chunker()).isEqualTo("kwiki-chunk-1");
        var custom = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                        java.util.Map.of("kwiki.indexing.defaults.chunker", "custom-chunker")))
                .bindOrCreate("kwiki.indexing.defaults", IndexingDefaultsProperties.class);
        assertThat(custom.chunker()).isEqualTo("custom-chunker");
        assertThat(custom.parsers()).containsExactly("kwiki-parse-2", "kwiki-parse-1");
    }
    @Test void 未注册默认分块器使Spring上下文启动失败() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(StartupConfig.class)
                .withPropertyValues("kwiki.indexing.defaults.chunker=missing-chunker")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage("默认分块器未注册：missing-chunker"));
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(IndexingDefaultsProperties.class)
    @org.springframework.context.annotation.Import(IndexingDefaultsValidator.class)
    static class StartupConfig {
        @org.springframework.context.annotation.Bean
        com.kwiki.indexing.schema.ChunkIndexSchemaRegistry schemas() { return SchemaTestFixtures.schemas(); }
        @org.springframework.context.annotation.Bean
        DocumentParserRegistry parsers() { return ParserTestFixtures.availableBoth(); }
        @org.springframework.context.annotation.Bean
        com.kwiki.indexing.chunk.plugin.ChunkerRegistry registry() { return chunkers(); }
    }
    @Test void 未注册默认分块器使启动失败() {
        assertThatThrownBy(() -> new IndexingDefaultsValidator(new IndexingDefaultsProperties("kwiki-schema-1",
                List.of("kwiki-parse-1"), "missing-chunker"), SchemaTestFixtures.schemas(),
                ParserTestFixtures.availableBoth(), chunkers())).hasMessage("默认分块器未注册：missing-chunker");
    }
}
