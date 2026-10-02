package com.kwiki.indexing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 首次部署、新建版本、灰度使用的默认插件；由应用的配置属性扫描登记。 */
@ConfigurationProperties(prefix = "kwiki.indexing.defaults")
public record IndexingDefaultsProperties(@DefaultValue("kwiki-schema-1") String indexSchema) {
}
