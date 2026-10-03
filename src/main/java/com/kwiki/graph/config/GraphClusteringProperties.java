package com.kwiki.graph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 社区检测（Leiden）的固定算法参数。 */
@ConfigurationProperties("kwiki.graph.clustering")
public record GraphClusteringProperties(@DefaultValue("10") int maxIterations,
                                        @DefaultValue("1.0") double resolution) {

    public GraphClusteringProperties {
        if (maxIterations < 1 || resolution <= 0) {
            throw new IllegalArgumentException("图聚类算法参数无效");
        }
    }
}
