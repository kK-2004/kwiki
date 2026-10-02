package com.kwiki.indexing.search;

import com.kwiki.indexing.multimodal.ProtectedBlockProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多模态分块文档投影契约（任务 8.1/8.2/8.4 单元层）：
 * contentIds 由受保护块重建、按首现去重；无资源分块不携带该
 * 字段（与 v1 严格映射写兼容）；v2 映射包含 contentIds 且
 * MappingValidator 按 schema 代校验。
 */
class MultimodalChunkDocumentTest {

    private final ProtectedBlockProtocol protocol = new ProtectedBlockProtocol(512);

    @Test
    void resourceContentIdsAreDeduplicatedByFirstOccurrence() {
        String content = "前文\n\n"
                + protocol.serialize(ProtectedBlockProtocol.ResourceRef.image(12345), "摘要一")
                + "\n\n中段\n\n"
                + protocol.serialize(ProtectedBlockProtocol.ResourceRef.image(12345), "摘要一")
                + "\n\n尾段\n\n"
                + protocol.serialize(ProtectedBlockProtocol.ResourceRef.image(67890), "摘要二");
        List<Long> contentIds = ChunkDocument.resourceContentIds(content);
        assertThat(contentIds).containsExactly(12345L, 67890L);
    }

    @Test
    void plainContentProducesNoResourceField() {
        assertThat(ChunkDocument.resourceContentIds("普通文本，没有任何资源标记。")).isEmpty();
        assertThat(ChunkDocument.resourceContentIds(null)).isEmpty();
        assertThat(ChunkDocument.resourceContentIds(
                "提到 <<KWIKI_META_DATA_START 字样的普通文本也被忽略")).isEmpty();
    }

    @Test
    void 字段格式必需资源字段缺失时拒绝() {
        var schema = new com.kwiki.indexing.schema.KwikiSchemaV1();
        var mapping = schema.mapping(1024);
        assertThat(MappingValidator.validate(mapping, 1024, schema.requiredFields())).isNull();
        var properties = new java.util.LinkedHashMap<>((Map<String, Object>)
                ((Map<?, ?>) mapping.get("mappings")).get("properties"));
        properties.remove("contentIds");
        assertThat(MappingValidator.validate(Map.of("mappings", Map.of("dynamic", "strict", "properties", properties)),
                1024, schema.requiredFields())).isEqualTo("missing required field: contentIds");
    }
}
