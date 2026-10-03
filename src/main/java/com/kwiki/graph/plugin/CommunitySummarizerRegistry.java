package com.kwiki.graph.plugin;

import com.kwiki.indexing.plugin.PluginRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 社区摘要插件的注册表；运行时按 id 解析。 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class CommunitySummarizerRegistry extends PluginRegistry<CommunitySummarizerPlugin> {

    public CommunitySummarizerRegistry(java.util.List<CommunitySummarizerPlugin> plugins) {
        super("摘要器", plugins);
    }
}
