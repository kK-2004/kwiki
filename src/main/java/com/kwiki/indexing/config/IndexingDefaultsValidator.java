package com.kwiki.indexing.config;

import com.kwiki.indexing.schema.ChunkIndexSchemaRegistry;
import org.springframework.stereotype.Component;

/** 启动时确认默认插件已注册，防止错误配置进入运行阶段。 */
@Component
public class IndexingDefaultsValidator {
    public IndexingDefaultsValidator(IndexingDefaultsProperties defaults, ChunkIndexSchemaRegistry schemas,
            com.kwiki.indexing.parse.plugin.DocumentParserRegistry parsers,
            com.kwiki.indexing.chunk.plugin.ChunkerRegistry chunkers) {
        if (chunkers.find(defaults.chunker()).isEmpty()) {
            throw new IllegalStateException("默认分块器未注册：" + defaults.chunker());
        }
        if (defaults.parsers() == null || defaults.parsers().isEmpty()) {
            throw new IllegalStateException("默认解析器偏好列表不能为空");
        }
        for (String id : defaults.parsers()) {
            if (parsers.find(id).isEmpty()) throw new IllegalStateException("默认解析器未注册：" + id);
        }
        String baseline = defaults.parsers().get(defaults.parsers().size() - 1);
        if (!parsers.require(baseline).availability().available()) {
            throw new IllegalStateException("默认基线解析器不可用：" + baseline);
        }
        if (schemas.find(defaults.indexSchema()).isEmpty()) {
            throw new IllegalStateException("默认字段格式未注册：" + defaults.indexSchema());
        }
    }
}
