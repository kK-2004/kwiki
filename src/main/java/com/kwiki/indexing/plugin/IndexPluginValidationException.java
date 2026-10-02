package com.kwiki.indexing.plugin;

/** 插件配置校验失败；仅通过固定模板生成可返回管理端的安全消息。 */
public final class IndexPluginValidationException extends IllegalArgumentException {
    private IndexPluginValidationException(String message) {
        super(message);
    }

    public static IndexPluginValidationException unavailableSchema(String schemaId) {
        return new IndexPluginValidationException("字段格式 " + schemaId + " 未注册或不可用");
    }

    public static IndexPluginValidationException missingCapability(String schemaId, String parserId,
                                                                   IndexCapability capability) {
        return new IndexPluginValidationException("字段格式 " + schemaId + " 不提供解析器 "
                + parserId + " 需要的能力 " + capability.name());
    }
}
