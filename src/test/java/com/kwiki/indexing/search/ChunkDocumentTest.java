package com.kwiki.indexing.search;

import com.kwiki.indexing.chunk.ParentChunk;
import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.pipeline.IndexedVersion;
import com.kwiki.indexing.schema.KwikiSchemaV1;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ChunkDocumentTest {
    private final KwikiSchemaV1 schema = new KwikiSchemaV1();
    private IndexedVersion version() {
        return new IndexedVersion("PAGE", 1, 2L, 1, 1, "kwiki-parse-1", "kwiki-chunk-1", "qwen", 1,
                List.of(), List.of(), List.of(), "kwiki-schema-1", "entity-linking-v1");
    }
    private ParentChunk parent(String content) {
        return new ParentChunk("p", 0, List.of(), 0, content.length(), content, ParentChunk.BoundaryType.HARD);
    }
    private ChildChunk child(String content) {
        return new ChildChunk("c", 0, "p", 0, content.length(), content, ChildChunk.BoundaryType.HARD);
    }
    @Test
    void 子块按字段格式追加图片与实体字段_父块只追加图片() {
        String content = new com.kwiki.indexing.multimodal.ProtectedBlockProtocol(512)
                .serialize(com.kwiki.indexing.multimodal.ProtectedBlockProtocol.ResourceRef.image(202), "摘要");
        assertThat(ChunkDocument.parent(parent(content), version(), schema)).containsEntry("contentIds", List.of(202L)).doesNotContainKey("entityIds");
        assertThat(ChunkDocument.child(child(content), new float[1024], version(), schema)).containsEntry("contentIds", List.of(202L))
                .containsEntry("entityIds", List.of()).containsEntry("entityLinkingStatus", "PENDING").containsKey("sourceChunkId");
    }
    @Test
    void 不含图片的分块不写contentIds() {
        assertThat(ChunkDocument.parent(parent("纯文本"), version(), schema)).doesNotContainKey("contentIds");
        assertThat(ChunkDocument.child(child("纯文本"), new float[1024], version(), schema)).doesNotContainKey("contentIds");
    }
}
