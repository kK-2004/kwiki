package com.kwiki.indexing.config;

import com.kwiki.indexing.schema.ChunkIndexSchemaRegistry;
import org.springframework.stereotype.Component;

/** 启动时确认默认字段格式已注册，防止错误配置进入运行阶段。 */
@Component
public class IndexingDefaultsValidator {
    public IndexingDefaultsValidator(IndexingDefaultsProperties defaults, ChunkIndexSchemaRegistry schemas) {
        if (schemas.find(defaults.indexSchema()).isEmpty()) {
            throw new IllegalStateException("默认字段格式未注册：" + defaults.indexSchema());
        }
    }
}
