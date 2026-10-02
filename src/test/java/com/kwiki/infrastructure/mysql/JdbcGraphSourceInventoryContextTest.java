package com.kwiki.infrastructure.mysql;

import com.kwiki.graph.GraphSourceInventoryPort;
import com.kwiki.graph.config.GraphProperties;
import com.kwiki.graph.config.GraphRetrievalEnhancerConfiguration;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.graph.persistence.GraphSnapshotReadService;
import com.kwiki.graph.persistence.GraphSourceEpochService;
import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.rag.retrieval.GraphRetrievalEnhancer;
import com.kwiki.wiki.access.KnowledgeBaseAuthorizationService;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcOperations;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JdbcGraphSourceInventoryContextTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(GraphConfiguration.class)
            .withConfiguration(AutoConfigurations.of(JdbcTemplateAutoConfiguration.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(ResourceAuthorizationService.class, () -> mock(ResourceAuthorizationService.class))
            .withBean(KnowledgeBaseAuthorizationService.class, () -> mock(KnowledgeBaseAuthorizationService.class))
            .withBean(GraphSourceEpochService.class, () -> mock(GraphSourceEpochService.class))
            .withBean(GraphBuildRepository.class, () -> mock(GraphBuildRepository.class))
            .withBean(ChunkEmbeddingPort.class, () -> mock(ChunkEmbeddingPort.class))
            .withBean(GraphSnapshotReadService.class, () -> mock(GraphSnapshotReadService.class));

    @Test
    void enabledGraphUsesInventoryWithAutoConfiguredJdbc() {
        context.withPropertyValues("kwiki.graph.enabled=true").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).hasSingleBean(JdbcOperations.class);
            assertThat(application).hasSingleBean(GraphSourceInventoryPort.class);
            assertThat(application.getBean(GraphSourceInventoryPort.class))
                    .isInstanceOf(JdbcGraphSourceInventory.class);
            assertThat(application).hasSingleBean(GraphRetrievalEnhancer.class);
        });
    }

    @Test
    void disabledGraphDoesNotRegisterInventoryOrEnhancer() {
        context.withPropertyValues("kwiki.graph.enabled=false").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).doesNotHaveBean(GraphSourceInventoryPort.class);
            assertThat(application).doesNotHaveBean(GraphRetrievalEnhancer.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GraphProperties.class)
    @Import(GraphRetrievalEnhancerConfiguration.class)
    @ComponentScan(basePackageClasses = JdbcGraphSourceInventory.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = JdbcGraphSourceInventory.class))
    static class GraphConfiguration {
    }
}
