package com.kwiki.indexing.plugin;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PluginRegistryTest {
    record P(String id, String label) implements IndexPlugin { }

    @Test
    void 按id查找并按id排序列出() {
        var registry = new PluginRegistry<>("字段格式", List.of(new P("b-2", "B"), new P("a-1", "A")));
        assertThat(registry.find("a-1")).isPresent();
        assertThat(registry.find("x")).isEmpty();
        assertThat(registry.all()).extracting(IndexPlugin::id).containsExactly("a-1", "b-2");
        assertThat(registry.require("b-2").label()).isEqualTo("B");
    }

    @Test
    void 未注册id抛出带类别与id的异常() {
        var registry = new PluginRegistry<>("字段格式", List.<P>of());
        assertThatThrownBy(() -> registry.require("legacy-schema-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字段格式").hasMessageContaining("legacy-schema-2");
    }

    @Test
    void id重复或格式非法启动失败() {
        assertThatThrownBy(() -> new PluginRegistry<>("字段格式", List.of(new P("a-1", "A"), new P("a-1", "B"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("a-1");
        assertThatThrownBy(() -> new PluginRegistry<>("字段格式", List.of(new P("Bad_Id", "A"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Bad_Id");
    }

    @Test
    void 默认可用() {
        assertThat(new P("a-1", "A").availability().available()).isTrue();
        assertThat(PluginAvailability.unavailable("缺少 X").unavailableReason()).isEqualTo("缺少 X");
    }
}
