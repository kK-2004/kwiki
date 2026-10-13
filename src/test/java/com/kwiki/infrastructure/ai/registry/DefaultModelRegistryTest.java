package com.kwiki.infrastructure.ai.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultModelRegistryTest {

    private final ModelSecretCipher cipher = new ModelSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private InMemoryModelConfigStore store;
    private CountingClientFactory factory;
    private DefaultModelRegistry registry;
    private long providerId;
    private long chatModelId;

    @BeforeEach
    void setUp() {
        store = new InMemoryModelConfigStore();
        factory = new CountingClientFactory();
        registry = new DefaultModelRegistry(store, cipher, factory);
        providerId = store.insertProvider(provider("bailian", "sk-secret-123456", true, null));
        chatModelId = store.insertModel(new ModelDefinition(null, providerId, "qwen-max", null,
                Set.of(ModelTag.CHAT), null, 8192, true, false, true));
        store.saveAssignments(List.of(
                new ModelAssignment(ModelPurpose.ROUTER, chatModelId, null, null, null),
                new ModelAssignment(ModelPurpose.ANSWER, chatModelId, null, null, null)));
    }

    private ModelProvider provider(String name, String key, boolean enabled, Integer concurrency) {
        return new ModelProvider(null, name, ModelProvider.OPENAI_COMPATIBLE, "https://example.test/v1",
                cipher.encrypt(key), ModelSecretCipher.hint(key), 30_000, 2, concurrency, null, enabled);
    }

    @Test
    void 按用途解析模型与默认参数() {
        assertThat(registry.reload()).isTrue();
        ResolvedModel router = registry.resolve(ModelPurpose.ROUTER);
        ResolvedModel answer = registry.resolve(ModelPurpose.ANSWER);
        assertThat(router.modelName()).isEqualTo("qwen-max");
        assertThat(router.temperature()).isEqualTo(0.0);
        assertThat(answer.temperature()).isEqualTo(0.2);
        assertThat(router.maxTokens()).isEqualTo(8192);
        assertThat(router.apiKey()).isEqualTo("sk-secret-123456");
        assertThat(router.toString()).doesNotContain("sk-secret");
        assertThat(registry.chat(ModelPurpose.ROUTER)).isNotNull();
        assertThat(registry.streaming(ModelPurpose.ANSWER)).isNotNull();
    }

    @Test
    void 指纹不变时复用客户端_参数变化只重建对应用途() {
        registry.reload();
        var routerBefore = registry.chat(ModelPurpose.ROUTER);
        var answerBefore = registry.streaming(ModelPurpose.ANSWER);
        store.saveAssignments(List.of(new ModelAssignment(ModelPurpose.ANSWER, chatModelId, 0.7, null, null)));
        store.bumpVersion();
        registry.reload();
        assertThat(registry.chat(ModelPurpose.ROUTER)).isSameAs(routerBefore);
        assertThat(registry.streaming(ModelPurpose.ANSWER)).isNotSameAs(answerBefore);
        assertThat(registry.resolve(ModelPurpose.ANSWER).temperature()).isEqualTo(0.7);
        assertThat(registry.status().version()).isEqualTo(1);
    }

    @Test
    void 未分配或停用时抛出未配置异常() {
        registry.reload();
        assertThatThrownBy(() -> registry.chat(ModelPurpose.AGENT_PLANNER))
                .isInstanceOf(ModelNotConfiguredException.class)
                .hasMessageContaining("AGENT_PLANNER");
        ModelDefinition model = store.models.get(chatModelId);
        store.updateModel(new ModelDefinition(model.id(), model.providerId(), model.modelName(), null,
                model.tags(), null, 8192, true, false, false));
        registry.reload();
        assertThat(registry.find(ModelPurpose.ROUTER)).isEmpty();
        store.updateModel(model);
        ModelProvider p = store.providers.get(providerId);
        store.updateProvider(new ModelProvider(p.id(), p.name(), p.protocol(), p.baseUrl(), p.apiKeyCipher(),
                p.apiKeyHint(), p.requestTimeoutMs(), p.maxRetries(), null, null, false));
        registry.reload();
        assertThatThrownBy(() -> registry.streaming(ModelPurpose.ANSWER))
                .isInstanceOf(ModelNotConfiguredException.class);
    }

    @Test
    void 从未加载时视为未配置() {
        assertThatThrownBy(() -> registry.chat(ModelPurpose.ROUTER))
                .isInstanceOf(ModelNotConfiguredException.class);
    }

    @Test
    void 解密失败时保留旧快照并记录脱敏错误() {
        registry.reload();
        var before = registry.chat(ModelPurpose.ROUTER);
        ModelProvider p = store.providers.get(providerId);
        store.updateProvider(new ModelProvider(p.id(), p.name(), p.protocol(), p.baseUrl(), "v1:broken",
                p.apiKeyHint(), p.requestTimeoutMs(), p.maxRetries(), null, null, true));
        store.bumpVersion();
        assertThat(registry.reload()).isFalse();
        assertThat(registry.chat(ModelPurpose.ROUTER)).isSameAs(before);
        assertThat(registry.status().version()).isZero();
        assertThat(registry.status().lastError()).contains("bailian").doesNotContain("sk-secret");
    }

    @Test
    void 未登记输出上限时探测一次并缓存() {
        ModelDefinition model = store.models.get(chatModelId);
        store.updateModel(new ModelDefinition(model.id(), model.providerId(), model.modelName(), null,
                model.tags(), null, null, true, false, true));
        factory.probedLimit = 4096;
        registry.reload();
        registry.reload();
        assertThat(registry.resolve(ModelPurpose.ROUTER).maxTokens()).isEqualTo(4096);
        assertThat(factory.probes).isEqualTo(1);
        store.saveAssignments(List.of(new ModelAssignment(ModelPurpose.ROUTER, chatModelId, null, 512, null)));
        registry.reload();
        assertThat(registry.resolve(ModelPurpose.ROUTER).maxTokens()).isEqualTo(512);
    }

    @Test
    void embedding按provider名与模型名定位() {
        long embeddingId = store.insertModel(new ModelDefinition(null, providerId, "text-embedding-v4", null,
                Set.of(ModelTag.EMBEDDING), 1024, null, false, false, true));
        registry.reload();
        assertThat(registry.embedding("bailian", "text-embedding-v4"))
                .hasValueSatisfying(b -> assertThat(b.dimensions()).isEqualTo(1024));
        assertThat(registry.embedding("bailian", "qwen-max")).isEmpty();
        assertThat(registry.embeddings()).extracting(EmbeddingBinding::identity)
                .containsExactly("bailian:text-embedding-v4:1024");
        assertThat(embeddingId).isPositive();
    }

    @Test
    void provider配置并发上限时共享限流器() {
        ModelProvider p = store.providers.get(providerId);
        store.updateProvider(new ModelProvider(p.id(), p.name(), p.protocol(), p.baseUrl(), p.apiKeyCipher(),
                p.apiKeyHint(), p.requestTimeoutMs(), p.maxRetries(), 3, 2.0, true));
        registry.reload();
        var router = registry.resolve(ModelPurpose.ROUTER);
        var answer = registry.resolve(ModelPurpose.ANSWER);
        assertThat(router.limiter()).isNotNull().isSameAs(answer.limiter());
        assertThat(router.limiter().permits()).isEqualTo(3);
        registry.reload();
        assertThat(registry.resolve(ModelPurpose.ROUTER).limiter()).isSameAs(router.limiter());
    }

    @Test
    void embedding默认每批10条_模型extra可覆盖并透传参数() {
        store.insertModel(new ModelDefinition(null, providerId, "text-embedding-v4", null,
                Set.of(ModelTag.EMBEDDING), 1024, null, false, false, true));
        registry.reload();
        assertThat(factory.embeddingBuilds).containsExactly("text-embedding-v4:1024:batch=10:{}");

        long id = store.models.values().stream().filter(m -> m.modelName().equals("text-embedding-v4"))
                .findFirst().orElseThrow().id();
        ModelDefinition model = store.models.get(id);
        store.updateModel(new ModelDefinition(id, providerId, model.modelName(), null, model.tags(), 1024, null,
                false, false, true, java.util.Map.of("batchSize", 25, "text_type", "document")));
        registry.reload();
        assertThat(factory.embeddingBuilds).last().isEqualTo("text-embedding-v4:1024:batch=25:{text_type=document}");
    }

    @Test
    void 用途extra覆盖模型extra_超时与透传参数生效且变化时重建客户端() {
        ModelDefinition model = store.models.get(chatModelId);
        store.updateModel(new ModelDefinition(chatModelId, providerId, model.modelName(), null, model.tags(), null,
                8192, true, false, true, java.util.Map.of("top_p", 0.5, "timeoutMs", 5000)));
        store.saveAssignments(List.of(new ModelAssignment(ModelPurpose.ROUTER, chatModelId, null, null, false,
                java.util.Map.of("top_p", 0.9))));
        registry.reload();

        ClientSpec spec = factory.chatBuilds.stream().filter(c -> Boolean.FALSE.equals(c.thinking()))
                .findFirst().orElseThrow();
        ClientSpec answer = factory.chatBuilds.stream().filter(c -> c.thinking() == null).findFirst().orElseThrow();
        assertThat(answer.parameters()).containsEntry("top_p", 0.5); // 未设用途 extra 的 ANSWER 取模型 extra
        assertThat(spec.parameters()).containsEntry("top_p", 0.9).doesNotContainKey("timeoutMs");
        assertThat(spec.customParameters()).containsEntry("enable_thinking", false);
        assertThat(spec.timeout()).isEqualTo(java.time.Duration.ofMillis(5000));
        assertThat(registry.resolve(ModelPurpose.ROUTER).extra()).containsEntry("top_p", 0.9);
        var before = registry.chat(ModelPurpose.ROUTER);

        store.saveAssignments(List.of(new ModelAssignment(ModelPurpose.ROUTER, chatModelId, null, null, false,
                java.util.Map.of("top_p", 0.7))));
        registry.reload();
        assertThat(registry.chat(ModelPurpose.ROUTER)).isNotSameAs(before);
    }
}
