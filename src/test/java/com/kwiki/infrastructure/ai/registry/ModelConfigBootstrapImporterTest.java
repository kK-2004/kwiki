package com.kwiki.infrastructure.ai.registry;

import com.kwiki.graph.config.GraphModelProperties;
import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.infrastructure.config.ExternalServicesProperties;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelConfigBootstrapImporterTest {

    private final ModelSecretCipher cipher = new ModelSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final InMemoryModelConfigStore store = new InMemoryModelConfigStore();

    private static ExternalServicesProperties external(ExternalServicesProperties.VisionModel vision) {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter("https://cc", "t", null, null,
                        Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new ExternalServicesProperties.Elasticsearch(null, null, null),
                new ExternalServicesProperties.AnswerLlm("https://llm.example/v1", "sk-answer-1234", "qwen-max",
                        Duration.ofSeconds(90)),
                new ExternalServicesProperties.QwenEmbedding("https://dashscope.example/v1", "sk-embed-1234",
                        "text-embedding-v4", 1024, Duration.ofSeconds(30)),
                vision);
    }

    private static IndexingProperties indexing(Map<String, IndexingProperties.EmbeddingProfile> profiles,
                                               List<IndexingProperties.Manifest> manifests) {
        return new IndexingProperties(manifests, profiles, new IndexingProperties.Rebuild(50, 2, 20),
                new IndexingProperties.Catchup(100, Duration.ofSeconds(30)),
                new IndexingProperties.Capacity(20, 8, Duration.ofSeconds(5)),
                new IndexingProperties.Management(false));
    }

    private ModelConfigBootstrapImporter importer(ExternalServicesProperties external, GraphModelProperties graph,
                                                  IndexingProperties indexing, ModelSecretCipher cipher) {
        return new ModelConfigBootstrapImporter(store, cipher, external, graph, indexing, Runnable::run);
    }

    private ModelProvider provider(String name) {
        return store.providers.values().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    private ModelDefinition model(String providerName, String modelName) {
        long providerId = provider(providerName).id();
        return store.models.values().stream()
                .filter(m -> m.providerId() == providerId && m.modelName().equals(modelName)).findFirst().orElseThrow();
    }

    @Test
    void 只有问答与embedding配置时导入并分配全部问答与图谱用途() {
        importer(external(ExternalServicesProperties.unusedVisionModel()), new GraphModelProperties(null, null),
                indexing(Map.of(), List.of()), cipher).importIfEmpty();

        ModelProvider answer = provider("answer-llm");
        assertThat(cipher.decrypt(answer.apiKeyCipher())).isEqualTo("sk-answer-1234");
        assertThat(answer.apiKeyHint()).isEqualTo("sk-****1234");
        assertThat(answer.requestTimeoutMs()).isEqualTo(90_000);
        long chatId = model("answer-llm", "qwen-max").id();
        assertThat(model("answer-llm", "qwen-max").tags()).containsExactly(ModelTag.CHAT);
        for (ModelPurpose purpose : ModelPurpose.values()) {
            if (purpose == ModelPurpose.VISION_SUMMARY) {
                assertThat(store.load().assignments().get(purpose).modelId()).isNull();
            } else {
                assertThat(store.load().assignments().get(purpose).modelId()).as(purpose.name()).isEqualTo(chatId);
            }
        }
        ModelDefinition embedding = model("default", "text-embedding-v4");
        assertThat(embedding.tags()).containsExactly(ModelTag.EMBEDDING);
        assertThat(embedding.dimensions()).isEqualTo(1024);
        assertThat(cipher.decrypt(provider("default").apiKeyCipher())).isEqualTo("sk-embed-1234");
        assertThat(store.version).isEqualTo(1);
        assertThat(store.locks).isEqualTo(1);
    }

    @Test
    void 图谱与vision配置复用相同连接的provider() {
        var graph = new GraphModelProperties(
                new GraphModelProperties.Model(null, null, "qwen-turbo", null, 8, 3.0, 4),
                new GraphModelProperties.Model("https://other.example/v1", "sk-other-9999", "glm-4", null, null, null, null));
        var vision = new ExternalServicesProperties.VisionModel("https://llm.example/v1/", "sk-answer-1234",
                "qwen-vl-max", Duration.ofSeconds(5), Duration.ofSeconds(60), 3, 4);
        importer(external(vision), graph, indexing(Map.of(), List.of()), cipher).importIfEmpty();

        assertThat(store.providers.values()).extracting(ModelProvider::name)
                .containsExactlyInAnyOrder("answer-llm", "graph-summary", "default");
        assertThat(store.load().assignments().get(ModelPurpose.GRAPH_EXTRACTION).modelId())
                .isEqualTo(model("answer-llm", "qwen-turbo").id());
        assertThat(store.load().assignments().get(ModelPurpose.GRAPH_SUMMARY).modelId())
                .isEqualTo(model("graph-summary", "glm-4").id());
        assertThat(store.load().assignments().get(ModelPurpose.VISION_SUMMARY).modelId())
                .isEqualTo(model("answer-llm", "qwen-vl-max").id());
        assertThat(model("answer-llm", "qwen-vl-max").tags()).containsExactly(ModelTag.VISION);
    }

    @Test
    void 命名embedding档案按档案名建provider并取清单中的模型与维度() {
        var profiles = Map.of("qwen-v5", new IndexingProperties.EmbeddingProfile("https://embed.example/v1",
                "sk-profile-5555", Duration.ofSeconds(45)));
        var manifests = List.of(new IndexingProperties.Manifest("gen-2", "kwiki-parse-1", "kwiki-chunk-1",
                "qwen-v5", "text-embedding-v5", 2048, "kwiki-schema-1"));
        importer(external(ExternalServicesProperties.unusedVisionModel()), new GraphModelProperties(null, null),
                indexing(profiles, manifests), cipher).importIfEmpty();

        ModelDefinition v5 = model("qwen-v5", "text-embedding-v5");
        assertThat(v5.dimensions()).isEqualTo(2048);
        assertThat(provider("qwen-v5").requestTimeoutMs()).isEqualTo(45_000);
    }

    @Test
    void 目录非空时跳过() {
        store.insertProvider(new ModelProvider(null, "existing", ModelProvider.OPENAI_COMPATIBLE, "https://x/v1",
                cipher.encrypt("k"), "****", 1000, 0, null, null, true));
        importer(external(ExternalServicesProperties.unusedVisionModel()), new GraphModelProperties(null, null),
                indexing(Map.of(), List.of()), cipher).importIfEmpty();

        assertThat(store.providers).hasSize(1);
        assertThat(store.version).isZero();
    }

    @Test
    void 没有旧配置时什么也不做() {
        var bare = new ExternalServicesProperties(null, null, null, null, ExternalServicesProperties.unusedVisionModel());
        importer(bare, new GraphModelProperties(null, null), indexing(Map.of(), List.of()), new ModelSecretCipher(""))
                .importIfEmpty();
        assertThat(store.providers).isEmpty();
    }

    @Test
    void 需要导入但缺少主密钥时明确失败() {
        assertThatThrownBy(() -> importer(external(ExternalServicesProperties.unusedVisionModel()),
                new GraphModelProperties(null, null), indexing(Map.of(), List.of()), new ModelSecretCipher(""))
                .importIfEmpty())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kwiki.model-config.master-key");
        assertThat(store.providers).isEmpty();
    }
}
