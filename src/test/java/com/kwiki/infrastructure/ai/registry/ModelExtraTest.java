package com.kwiki.infrastructure.ai.registry;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelExtraTest {

    @Test
    void 校验键名_保留键与已知键取值() {
        assertThat(ModelExtra.normalize(null)).isEmpty();
        assertThat(ModelExtra.normalize(Map.of("top_p", 0.8, "batchSize", 10))).containsEntry("top_p", 0.8);
        assertThatThrownBy(() -> ModelExtra.normalize(Map.of("temperature", 0.1)))
                .hasMessageContaining("temperature");
        assertThatThrownBy(() -> ModelExtra.normalize(Map.of("enable_thinking", true)))
                .hasMessageContaining("enable_thinking");
        assertThatThrownBy(() -> ModelExtra.normalize(Map.of("bad key", 1))).hasMessageContaining("参数名不合法");
        assertThatThrownBy(() -> ModelExtra.normalize(Map.of("batchSize", 0))).hasMessageContaining("1–2048");
        assertThatThrownBy(() -> ModelExtra.normalize(Map.of("batchSize", 2.5))).hasMessageContaining("整数");
        assertThatThrownBy(() -> ModelExtra.normalize(Map.of("timeoutMs", "fast"))).hasMessageContaining("timeoutMs");
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("seed", null);
        assertThatThrownBy(() -> ModelExtra.normalize(withNull)).hasMessageContaining("不能为空");
    }

    @Test
    void 用途覆盖模型_已知键不透传() {
        Map<String, Object> merged = ModelExtra.merge(Map.of("top_p", 0.5, "batchSize", 25),
                Map.of("top_p", 0.9, "seed", 7));
        assertThat(merged).containsEntry("top_p", 0.9).containsEntry("seed", 7).containsEntry("batchSize", 25);
        assertThat(ModelExtra.passthrough(merged)).containsOnlyKeys("seed", "top_p");
        assertThat(ModelExtra.intValue(merged, ModelExtra.BATCH_SIZE)).isEqualTo(25);
    }

    @Test
    void JSON往返() {
        Map<String, Object> extra = Map.of("enable_search", true, "options", Map.of("a", 1));
        assertThat(ModelExtra.fromJson(ModelExtra.toJson(extra))).isEqualTo(extra);
        assertThat(ModelExtra.toJson(Map.of())).isNull();
        assertThat(ModelExtra.fromJson(null)).isEmpty();
    }
}
