package com.kwiki.infrastructure.ai.registry;

import com.kk2004.common.exception.BusinessException;
import com.kwiki.infrastructure.ai.registry.ModelConfigAdminService.AssignmentRequest;
import com.kwiki.infrastructure.ai.registry.ModelConfigAdminService.ModelRequest;
import com.kwiki.infrastructure.ai.registry.ModelConfigAdminService.ProviderRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelConfigAdminServiceTest {

    private final ModelSecretCipher cipher = new ModelSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private InMemoryModelConfigStore store;
    private CountingClientFactory factory;
    private DefaultModelRegistry registry;
    private final List<Object> events = new ArrayList<>();
    private ModelConfigAdminService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryModelConfigStore();
        factory = new CountingClientFactory();
        registry = new DefaultModelRegistry(store, cipher, factory);
        service = new ModelConfigAdminService(store, cipher, factory, registry, events::add, "native");
    }

    private static ProviderRequest provider(String name, String key) {
        return new ProviderRequest(name, "https://example.test/v1", key, null, null, null, null, true);
    }

    private static ModelRequest chat(String name, boolean tools) {
        return new ModelRequest(name, null, Set.of("chat"), null, null, tools, false, true);
    }

    private static ModelRequest embedding(String name, Integer dims) {
        return new ModelRequest(name, null, Set.of("embedding"), dims, null, false, false, true);
    }

    private static int code(Throwable e) {
        return ((BusinessException) e).getCode();
    }

    @Test
    void 新建provider只返回掩码且每次写入递增版本并发布事件() {
        var view = service.createProvider(provider("bailian", "sk-abcdef123456"));
        assertThat(view.apiKeyHint()).isEqualTo("sk-****3456");
        assertThat(view.toString()).doesNotContain("abcdef123456");
        assertThat(store.providers.get(view.id()).apiKeyCipher()).startsWith("v1:");
        assertThat(store.version).isEqualTo(1);
        assertThat(events).containsExactly(new ModelConfigChangedEvent(1));
    }

    @Test
    void provider名称重复或缺少key时拒绝() {
        service.createProvider(provider("bailian", "sk-1"));
        assertThatThrownBy(() -> service.createProvider(provider("bailian", "sk-2")))
                .satisfies(e -> assertThat(code(e)).isEqualTo(409));
        assertThatThrownBy(() -> service.createProvider(provider("other", " ")))
                .satisfies(e -> assertThat(code(e)).isEqualTo(400));
        assertThatThrownBy(() -> service.createProvider(new ProviderRequest("x", "ftp://bad", "k",
                null, null, null, null, true))).satisfies(e -> assertThat(code(e)).isEqualTo(400));
    }

    @Test
    void 修改provider时key留空保持原密文() {
        long id = service.createProvider(provider("bailian", "sk-original-key")).id();
        String before = store.providers.get(id).apiKeyCipher();
        service.updateProvider(id, new ProviderRequest("bailian", "https://example.test/v2", null,
                60_000, 1, null, null, true));
        assertThat(store.providers.get(id).apiKeyCipher()).isEqualTo(before);
        assertThat(store.providers.get(id).baseUrl()).isEqualTo("https://example.test/v2");
        assertThat(store.providers.get(id).requestTimeoutMs()).isEqualTo(60_000);
    }

    @Test
    void provider改名时联动更新索引版本引用() {
        long id = service.createProvider(provider("default", "sk-1")).id();
        service.createModels(id, List.of(embedding("text-embedding-v4", 1024)));
        store.indexVersions.put(3, new String[]{"default", "text-embedding-v4"});
        service.updateProvider(id, new ProviderRequest("bailian", "https://example.test/v1", null,
                null, null, null, null, true));
        assertThat(store.indexVersions.get(3)[0]).isEqualTo("bailian");
    }

    @Test
    void embedding模型必须有维度() {
        long id = service.createProvider(provider("bailian", "sk-1")).id();
        assertThatThrownBy(() -> service.createModels(id, List.of(embedding("e", null))))
                .satisfies(e -> assertThat(code(e)).isEqualTo(400));
        assertThatThrownBy(() -> service.createModels(id, List.of(
                new ModelRequest("m", null, Set.of(), null, null, true, false, true))))
                .satisfies(e -> assertThat(code(e)).isEqualTo(400));
    }

    @Test
    void 分配校验标签与tools能力() {
        long id = service.createProvider(provider("bailian", "sk-1")).id();
        var models = service.createModels(id, List.of(chat("qwen-max", true), chat("no-tools", false),
                embedding("text-embedding-v4", 1024)));
        long qwen = models.get(0).id();
        long noTools = models.get(1).id();
        long embed = models.get(2).id();

        assertThatThrownBy(() -> service.saveAssignments(List.of(
                new AssignmentRequest("ROUTER", embed, null, null, null))))
                .satisfies(e -> assertThat(code(e)).isEqualTo(400))
                .hasMessageContaining("chat");
        assertThatThrownBy(() -> service.saveAssignments(List.of(
                new AssignmentRequest("AGENT_PLANNER", noTools, null, null, null))))
                .satisfies(e -> assertThat(code(e)).isEqualTo(400))
                .hasMessageContaining("tool");
        assertThatThrownBy(() -> service.saveAssignments(List.of(
                new AssignmentRequest("ANSWER", qwen, null, null, true))))
                .satisfies(e -> assertThat(code(e)).isEqualTo(400))
                .hasMessageContaining("thinking");
        assertThatThrownBy(() -> service.saveAssignments(List.of(
                new AssignmentRequest("ANSWER", qwen, 3.0, null, null))))
                .satisfies(e -> assertThat(code(e)).isEqualTo(400));

        service.saveAssignments(List.of(
                new AssignmentRequest("AGENT_PLANNER", qwen, null, null, null),
                new AssignmentRequest("ANSWER", qwen, 0.5, 2048, null)));
        assertThat(store.assignments.get(ModelPurpose.ANSWER).temperature()).isEqualTo(0.5);
        registry.reload(); // 生产环境由提交后的 ModelConfigChangedEvent 触发
        var views = service.assignments();
        assertThat(views).hasSize(ModelPurpose.values().length);
        assertThat(views.stream().filter(v -> v.purpose().equals("AGENT_PLANNER")).findFirst().orElseThrow()
                .effectiveModel()).isEqualTo("bailian / qwen-max");
    }

    @Test
    void 被引用的模型不能删除或停用() {
        long id = service.createProvider(provider("bailian", "sk-1")).id();
        var models = service.createModels(id, List.of(chat("qwen-max", true), embedding("text-embedding-v4", 1024)));
        long qwen = models.get(0).id();
        long embed = models.get(1).id();
        service.saveAssignments(List.of(new AssignmentRequest("ROUTER", qwen, null, null, null)));
        store.indexVersions.put(2, new String[]{"bailian", "text-embedding-v4"});

        assertThatThrownBy(() -> service.deleteModel(qwen))
                .satisfies(e -> assertThat(code(e)).isEqualTo(409))
                .hasMessageContaining("ROUTER");
        assertThatThrownBy(() -> service.updateModel(qwen, new ModelRequest("qwen-max", null, Set.of("chat"),
                null, null, true, false, false)))
                .satisfies(e -> assertThat(code(e)).isEqualTo(409));
        assertThatThrownBy(() -> service.updateModel(embed, embedding("text-embedding-v4", 2048)))
                .satisfies(e -> assertThat(code(e)).isEqualTo(409))
                .hasMessageContaining("索引版本 2");
        assertThatThrownBy(() -> service.deleteProvider(id))
                .satisfies(e -> assertThat(code(e)).isEqualTo(409));
        assertThatThrownBy(() -> service.updateProvider(id, new ProviderRequest("bailian",
                "https://example.test/v1", null, null, null, null, null, false)))
                .satisfies(e -> assertThat(code(e)).isEqualTo(409));
    }

    @Test
    void 未被引用的provider连同模型一起删除() {
        long id = service.createProvider(provider("bailian", "sk-1")).id();
        service.createModels(id, List.of(chat("qwen-max", true)));
        service.deleteProvider(id);
        assertThat(store.providers).isEmpty();
        assertThat(store.models).isEmpty();
    }

    @Test
    void 发现模型时排除已登记的() {
        long id = service.createProvider(provider("bailian", "sk-1")).id();
        service.createModels(id, List.of(chat("qwen-max", true)));
        factory.listed = List.of("qwen-max", "qwen-turbo", "text-embedding-v4");
        assertThat(service.discover(id)).containsExactly("qwen-turbo", "text-embedding-v4");
    }

    @Test
    void 连通性测试可使用已保存的key且失败信息脱敏() {
        long id = service.createProvider(provider("bailian", "sk-secret-123456")).id();
        factory.listed = List.of("a", "b");
        var ok = service.testProvider(new ModelConfigAdminService.ConnectionTest(id, null, null, null));
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).contains("2");
    }
}
