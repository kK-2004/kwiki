package com.kwiki.graph.plugin;

import com.kwiki.indexing.plugin.PluginRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 同类抽取插件的注册表；运行时按 id 解析。 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphExtractorRegistry extends PluginRegistry<GraphExtractorPlugin> {

    public GraphExtractorRegistry(java.util.List<GraphExtractorPlugin> plugins) {
        super("抽取器", plugins);
    }
}
