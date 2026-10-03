package com.kwiki.graph.config;

import com.kwiki.graph.ConservativeGraphEntityResolver;
import com.kwiki.graph.GraphEntityRegistryPort;
import com.kwiki.graph.GraphEntityResolutionPort;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.graph.persistence.GraphBuildRunCoordinator;
import com.kwiki.graph.persistence.GraphSnapshotValidationService;
import com.kwiki.graph.persistence.GraphSourceManifestBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 图构建执行链路使用的内部组件装配。此前这些类没有任何调用方，
 * 统一在此注册；GraphExtractionJobPlanner 已通过 @Repository 注册，不在此重复。
 */
@Configuration
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphBuildComponentsConfiguration {

    @Bean
    GraphEntityResolutionPort graphEntityResolver(GraphEntityRegistryPort registry) {
        return new ConservativeGraphEntityResolver(registry);
    }

    @Bean
    GraphSnapshotValidationService graphSnapshotValidationService() {
        return new GraphSnapshotValidationService();
    }

    @Bean
    GraphSourceManifestBuilder graphSourceManifestBuilder() {
        return new GraphSourceManifestBuilder();
    }

    @Bean
    GraphBuildRunCoordinator graphBuildRunCoordinator(GraphBuildRepository repository) {
        return new GraphBuildRunCoordinator(repository);
    }
}
