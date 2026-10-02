package com.kwiki.indexing.chunk.plugin;

import com.kwiki.indexing.plugin.PluginRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/** 收集当前部署中的分块器插件并按 id 解析。 */
@Component
public class ChunkerRegistry extends PluginRegistry<ChunkerPlugin> {
    public ChunkerRegistry(List<ChunkerPlugin> chunkers) {
        super("分块器", chunkers);
    }
}
