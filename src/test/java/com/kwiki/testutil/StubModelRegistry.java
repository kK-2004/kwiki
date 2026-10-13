package com.kwiki.testutil;

import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.infrastructure.ai.registry.EmbeddingBinding;
import com.kwiki.infrastructure.ai.registry.ModelNotConfiguredException;
import com.kwiki.infrastructure.ai.registry.ModelPurpose;
import com.kwiki.infrastructure.ai.registry.ModelRegistry;
import com.kwiki.infrastructure.ai.registry.ResolvedModel;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 测试用模型目录：按需登记用途与 embedding 模型，未登记的用途视为未配置。 */
public final class StubModelRegistry implements ModelRegistry {

    private final Map<ModelPurpose, ResolvedModel> resolved = new EnumMap<>(ModelPurpose.class);
    private final Map<ModelPurpose, ChatModel> chats = new EnumMap<>(ModelPurpose.class);
    private final Map<String, EmbeddingBinding> embeddings = new LinkedHashMap<>();

    public static StubModelRegistry empty() {
        return new StubModelRegistry();
    }

    /** 只有一个 default provider embedding 模型的目录，对应迁移前 kwiki.qwen-embedding 的部署形态。 */
    public static StubModelRegistry defaultEmbedding(String model, int dimensions, ChunkEmbeddingPort port) {
        return new StubModelRegistry().withEmbedding("default", model, dimensions, port);
    }

    public StubModelRegistry withEmbedding(String provider, String model, int dimensions, ChunkEmbeddingPort port) {
        embeddings.put(provider + "\u0000" + model, new EmbeddingBinding(provider, model, dimensions, port));
        return this;
    }

    public StubModelRegistry withPurpose(ModelPurpose purpose, String modelName, ChatModel chat) {
        resolved.put(purpose, new ResolvedModel(purpose, 1, "stub", "http://localhost/v1", "stub-key", modelName,
                purpose.defaultTemperature(), null, null, 2, Duration.ofSeconds(30), null, null, Map.of()));
        if (chat != null) chats.put(purpose, chat);
        return this;
    }

    @Override
    public ChatModel chat(ModelPurpose purpose) {
        ChatModel chat = chats.get(purpose);
        if (chat == null) throw new ModelNotConfiguredException(purpose);
        return chat;
    }

    @Override
    public StreamingChatModel streaming(ModelPurpose purpose) {
        throw new ModelNotConfiguredException(purpose);
    }

    @Override
    public ResolvedModel resolve(ModelPurpose purpose) {
        ResolvedModel model = resolved.get(purpose);
        if (model == null) throw new ModelNotConfiguredException(purpose);
        return model;
    }

    @Override
    public Optional<EmbeddingBinding> embedding(String providerName, String modelName) {
        return Optional.ofNullable(embeddings.get(providerName + "\u0000" + modelName));
    }

    @Override
    public List<EmbeddingBinding> embeddings() {
        return List.copyOf(embeddings.values());
    }

    @Override
    public Status status() {
        return new Status(1, Instant.EPOCH, null);
    }
}
