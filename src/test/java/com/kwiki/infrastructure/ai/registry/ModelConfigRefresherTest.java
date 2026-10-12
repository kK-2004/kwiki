package com.kwiki.infrastructure.ai.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelConfigRefresherTest {

    private InMemoryModelConfigStore store;
    private DefaultModelRegistry registry;
    private final List<Long> published = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private ModelConfigRefresher refresher;

    @BeforeEach
    void setUp() {
        store = new InMemoryModelConfigStore();
        registry = new DefaultModelRegistry(store,
                new ModelSecretCipher(Base64.getEncoder().encodeToString(new byte[32])), new CountingClientFactory());
        refresher = new ModelConfigRefresher(registry, store, provider(published::add),
                provider(() -> calls.add("import")));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }

    @Test
    void 启动时先导入再加载() {
        store.version = 3;
        refresher.afterSingletonsInstantiated();
        assertThat(calls).containsExactly("import");
        assertThat(registry.loadedVersion()).isEqualTo(3);
    }

    @Test
    void 导入失败不阻止加载() {
        refresher = new ModelConfigRefresher(registry, store, provider(published::add),
                provider(() -> { throw new IllegalStateException("boom"); }));
        store.version = 1;
        refresher.afterSingletonsInstantiated();
        assertThat(registry.loadedVersion()).isEqualTo(1);
    }

    @Test
    void 本机写入提交后重新加载并广播() {
        store.version = 5;
        refresher.onLocalChange(new ModelConfigChangedEvent(5));
        assertThat(registry.loadedVersion()).isEqualTo(5);
        assertThat(published).containsExactly(5L);
    }

    @Test
    void 只处理更高版本的广播() {
        store.version = 2;
        registry.reload();
        store.version = 4;
        refresher.onRemoteVersion(2);
        assertThat(registry.loadedVersion()).isEqualTo(2);
        refresher.onRemoteVersion(4);
        assertThat(registry.loadedVersion()).isEqualTo(4);
    }

    @Test
    void 轮询发现版本变化时重新加载() {
        registry.reload();
        store.version = 7;
        refresher.poll();
        assertThat(registry.loadedVersion()).isEqualTo(7);
    }
}
