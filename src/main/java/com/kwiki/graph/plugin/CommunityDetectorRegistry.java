package com.kwiki.graph.plugin;

import com.kwiki.indexing.plugin.PluginRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 社区检测插件的注册表；运行时按 id 解析。 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class CommunityDetectorRegistry extends PluginRegistry<CommunityDetectorPlugin> {

    public CommunityDetectorRegistry(java.util.List<CommunityDetectorPlugin> plugins) {
        super("社区检测", plugins);
    }
}
