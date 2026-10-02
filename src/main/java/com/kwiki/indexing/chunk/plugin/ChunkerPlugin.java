package com.kwiki.indexing.chunk.plugin;

import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ParentChunk;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.plugin.IndexPlugin;

import java.util.List;

/** 分块器插件：将结构化文档转换为父块及其子块。 */
public interface ChunkerPlugin extends IndexPlugin {
    List<ParentChunk> parents(String keyPrefix, StructuredDocument document);

    List<ChildChunk> children(ParentChunk parent, StructuredDocument document);
}
