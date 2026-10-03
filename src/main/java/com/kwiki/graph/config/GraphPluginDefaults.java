package com.kwiki.graph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 图构建三个可替换环节的默认插件 id；提交批次缺省时取用。 */
@ConfigurationProperties("kwiki.graph.defaults")
public record GraphPluginDefaults(@DefaultValue("kwiki-extractor-1") String extractor,
                                  @DefaultValue("arcadedb-leiden-1") String communityDetector,
                                  @DefaultValue("kwiki-summarizer-1") String summarizer) {

    public GraphPluginDefaults {
        if (extractor == null || extractor.isBlank()
                || communityDetector == null || communityDetector.isBlank()
                || summarizer == null || summarizer.isBlank()) {
            throw new IllegalArgumentException("图构建默认插件配置无效");
        }
    }
}
