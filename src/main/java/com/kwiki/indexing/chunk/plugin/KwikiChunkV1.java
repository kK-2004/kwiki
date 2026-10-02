package com.kwiki.indexing.chunk.plugin;

import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ChildChunker;
import com.kwiki.indexing.chunk.ChunkingConfig;
import com.kwiki.indexing.chunk.ParentChunk;
import com.kwiki.indexing.chunk.ParentChunker;
import com.kwiki.indexing.multimodal.MultimodalMetrics;
import com.kwiki.indexing.parse.StructuredDocument;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/** 使用既有默认配置和父子分块算法的第一版插件。 */
@Component
public class KwikiChunkV1 implements ChunkerPlugin {
    public static final String ID = "kwiki-chunk-1";

    private final ParentChunker parentChunker;
    private final ChildChunker childChunker;

    public KwikiChunkV1(ObjectProvider<MultimodalMetrics> metrics) {
        MultimodalMetrics availableMetrics = metrics.getIfAvailable();
        this.parentChunker = new ParentChunker(ChunkingConfig.defaults(), availableMetrics);
        this.childChunker = new ChildChunker(ChunkingConfig.defaults(), availableMetrics);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "父子分块 v1";
    }

    @Override
    public List<ParentChunk> parents(String keyPrefix, StructuredDocument document) {
        return parentChunker.chunk(keyPrefix, document);
    }

    @Override
    public List<ChildChunk> children(ParentChunk parent, StructuredDocument document) {
        return childChunker.chunk(parent, document);
    }
}
