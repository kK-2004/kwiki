package com.kwiki.indexing.pipeline;

import java.util.List;

/** 索引构建工作线程使用的向量嵌入边界（由千问适配器实现）。 */
public interface ChunkEmbeddingPort {

    default String cacheIdentity() {
        return getClass().getName();
    }

    /**
     * 按顺序为文本生成向量嵌入；结果数量必须等于输入数量，且与配置的维度一致。
     */
    List<float[]> embed(List<String> texts);
}
