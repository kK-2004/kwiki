package com.kwiki.indexing.parse.plugin;

import com.kwiki.indexing.plugin.PluginRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/** 收集解析器插件并通过公共注册表校验与查找。 */
@Component
public class DocumentParserRegistry extends PluginRegistry<DocumentParserPlugin> {
    public DocumentParserRegistry(List<DocumentParserPlugin> parsers) {
        super("解析器", parsers);
    }
}
