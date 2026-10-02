package com.kwiki.indexing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 首次部署、新建版本、灰度使用的默认插件；由应用的配置属性扫描登记。 */
@ConfigurationProperties(prefix = "kwiki.indexing.defaults")
public record IndexingDefaultsProperties(@DefaultValue("kwiki-schema-1") String indexSchema,
        @DefaultValue({"kwiki-parse-2", "kwiki-parse-1"}) java.util.List<String> parsers,
        @DefaultValue("kwiki-chunk-1") String chunker) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public IndexingDefaultsProperties { }

    public IndexingDefaultsProperties(String indexSchema, java.util.List<String> parsers) {
        this(indexSchema, parsers, "kwiki-chunk-1");
    }

    public IndexingDefaultsProperties(String indexSchema) {
        this(indexSchema, java.util.List.of("kwiki-parse-2", "kwiki-parse-1"), "kwiki-chunk-1");
    }
}
