package com.kwiki.graph.config;

import org.springframework.context.annotation.Configuration;

/** 在应用启动期执行图功能与 ArcadeDB 配置的组合校验。 */
@Configuration
public class GraphConfigurationGuard {

    public GraphConfigurationGuard(GraphProperties graph, ArcadeDbProperties arcadeDb) {
        arcadeDb.requireUsableWhenEnabled(graph);
    }
}
