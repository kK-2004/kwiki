package com.kwiki.indexing.plugin;

/** 字段格式提供、解析器需要的能力；兼容判断与图构建门槛据此进行。 */
public enum IndexCapability {
    /** 文档携带图片 contentIds。 */
    RESOURCE_IDS,
    /** 子块携带实体关联字段，可用于图构建。 */
    ENTITY_LINKING
}
