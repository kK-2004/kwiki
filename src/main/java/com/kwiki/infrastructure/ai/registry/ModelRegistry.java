package com.kwiki.infrastructure.ai.registry;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 运行时模型入口：调用方在每次调用开始时按用途取一次客户端，调用过程中不再重新获取，
 * 因此配置热更新不会影响进行中的请求。用途不可用时抛出 {@link ModelNotConfiguredException}。
 */
public interface ModelRegistry {

    ChatModel chat(ModelPurpose purpose);

    StreamingChatModel streaming(ModelPurpose purpose);

    ResolvedModel resolve(ModelPurpose purpose);

    default Optional<ResolvedModel> find(ModelPurpose purpose) {
        try {
            return Optional.of(resolve(purpose));
        } catch (ModelNotConfiguredException e) {
            return Optional.empty();
        }
    }

    Optional<EmbeddingBinding> embedding(String providerName, String modelName);

    List<EmbeddingBinding> embeddings();

    Status status();

    record Status(long version, Instant loadedAt, String lastError) {
    }

    /** 测试辅助：所有用途都返回给定的同步客户端。 */
    static ModelRegistry fixed(ChatModel chat) {
        return fixed(chat, null);
    }

    /** 测试辅助：所有用途都返回给定的流式客户端。 */
    static ModelRegistry fixed(StreamingChatModel streaming) {
        return fixed(null, streaming);
    }

    /** 测试辅助：所有用途都返回给定客户端，没有 embedding。 */
    static ModelRegistry fixed(ChatModel chat, StreamingChatModel streaming) {
        return new ModelRegistry() {
            @Override
            public ChatModel chat(ModelPurpose purpose) {
                if (chat == null) throw new ModelNotConfiguredException(purpose);
                return chat;
            }

            @Override
            public StreamingChatModel streaming(ModelPurpose purpose) {
                if (streaming == null) throw new ModelNotConfiguredException(purpose);
                return streaming;
            }

            @Override
            public ResolvedModel resolve(ModelPurpose purpose) {
                return new ResolvedModel(purpose, 0, "fixed", "http://localhost", "fixed", "fixed",
                        purpose.defaultTemperature(), null, null, 0, Duration.ofSeconds(120), null, null);
            }

            @Override
            public Optional<EmbeddingBinding> embedding(String providerName, String modelName) {
                return Optional.empty();
            }

            @Override
            public List<EmbeddingBinding> embeddings() {
                return List.of();
            }

            @Override
            public Status status() {
                return new Status(0, Instant.EPOCH, null);
            }
        };
    }
}
