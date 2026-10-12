package com.kwiki.infrastructure.ai.registry;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;

/** 记录构建次数的客户端工厂，客户端本身是 mock。 */
class CountingClientFactory implements ModelClientFactory {

    final List<ClientSpec> chatBuilds = new ArrayList<>();
    final List<ClientSpec> streamingBuilds = new ArrayList<>();
    final List<String> embeddingBuilds = new ArrayList<>();
    Integer probedLimit;
    int probes;

    @Override
    public ChatModel chat(ClientSpec spec) {
        chatBuilds.add(spec);
        return mock(ChatModel.class);
    }

    @Override
    public StreamingChatModel streaming(ClientSpec spec) {
        streamingBuilds.add(spec);
        return mock(StreamingChatModel.class);
    }

    @Override
    public ChunkEmbeddingPort embedding(String baseUrl, String apiKey, Duration timeout, String modelName,
                                        int dimensions, int maxRetries) {
        embeddingBuilds.add(modelName + ":" + dimensions);
        return texts -> List.of();
    }

    @Override
    public Integer probeOutputLimit(String baseUrl, String apiKey, Duration timeout, String modelName) {
        probes++;
        return probedLimit;
    }

    List<String> listed = List.of();

    @Override
    public List<String> listModels(String baseUrl, String apiKey, Duration timeout) {
        return listed;
    }
}
