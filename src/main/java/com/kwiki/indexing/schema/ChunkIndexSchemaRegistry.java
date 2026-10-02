package com.kwiki.indexing.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kwiki.indexing.plugin.PluginRegistry;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 字段格式注册表；映射摘要统一在此计算，插件不负责。 */
@Component
public class ChunkIndexSchemaRegistry extends PluginRegistry<ChunkIndexSchema> {
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public ChunkIndexSchemaRegistry(List<ChunkIndexSchema> schemas) {
        super("字段格式", schemas);
    }

    public String mappingHash(String schemaId, int embeddingDimensions) {
        return mappingHash(require(schemaId).mapping(embeddingDimensions));
    }

    /** 与原映射构建器一致：按 key 排序 JSON，再对 UTF-8 字节计算 SHA-256。 */
    public static String mappingHash(Map<String, Object> mapping) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(CANONICAL.writeValueAsString(mapping).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalStateException("mapping hash failed", failure);
        }
    }
}
