package com.kwiki.infrastructure.ai.registry;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;

/** 目录中一个可用的 embedding 模型及其客户端；按「provider 名 + 模型名」定位。 */
public record EmbeddingBinding(String providerName, String modelName, int dimensions, ChunkEmbeddingPort port) {

    public String identity() {
        return providerName + ":" + modelName + ":" + dimensions;
    }
}
