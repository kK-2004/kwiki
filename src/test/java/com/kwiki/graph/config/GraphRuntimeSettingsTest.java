package com.kwiki.graph.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphRuntimeSettingsTest {

    private static GraphRuntimeSettings settings(MockEnvironment env) {
        GraphRuntimeSettings settings = new GraphRuntimeSettings(
                env.getProperty("kwiki.graph.auto-publish", Boolean.class, false),
                env.getProperty("kwiki.graph.rebuild.enabled", Boolean.class, true),
                env.getProperty("kwiki.graph.rebuild.quiet-period", "30m"),
                env.getProperty("kwiki.graph.rebuild.max-delay", "6h"),
                env.getProperty("kwiki.graph.rebuild.auto-publish", Boolean.class, true));
        settings.setEnvironment(env);
        return settings;
    }

    @Test
    void 代码默认值不依赖配置文件() {
        GraphRuntimeSettings.Snapshot snapshot = settings(new MockEnvironment()).snapshot();
        assertThat(snapshot.autoPublish()).isFalse();
        assertThat(snapshot.rebuildEnabled()).isTrue();
        assertThat(snapshot.rebuildQuietPeriod()).isEqualTo(Duration.ofMinutes(30));
        assertThat(snapshot.rebuildMaxDelay()).isEqualTo(Duration.ofHours(6));
        assertThat(snapshot.rebuildAutoPublish()).isTrue();
    }

    @Test
    void 启动时同时支持简写与ISO8601时长() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("kwiki.graph.rebuild.quiet-period", "PT45M")
                .withProperty("kwiki.graph.rebuild.max-delay", "2h");
        GraphRuntimeSettings.Snapshot snapshot = settings(env).snapshot();
        assertThat(snapshot.rebuildQuietPeriod()).isEqualTo(Duration.ofMinutes(45));
        assertThat(snapshot.rebuildMaxDelay()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void 启动时配置非法直接失败() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("kwiki.graph.rebuild.quiet-period", "not-a-duration");
        assertThatThrownBy(() -> settings(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quiet-period");
    }

    @Test
    void 热更新生效并记录整组替换() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("kwiki.graph.auto-publish", "true");
        GraphRuntimeSettings settings = settings(env);
        assertThat(settings.snapshot().autoPublish()).isTrue();

        env.setProperty("kwiki.graph.auto-publish", "false");
        env.setProperty("kwiki.graph.rebuild.quiet-period", "15m");
        assertThat(settings.reload(env)).isTrue();
        assertThat(settings.snapshot().autoPublish()).isFalse();
        assertThat(settings.snapshot().rebuildQuietPeriod()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void 非法组合整组拒绝并保留原值() {
        MockEnvironment env = new MockEnvironment();
        GraphRuntimeSettings settings = settings(env);

        env.setProperty("kwiki.graph.rebuild.quiet-period", "2h");
        env.setProperty("kwiki.graph.rebuild.max-delay", "1h");
        assertThat(settings.reload(env)).isFalse();
        assertThat(settings.snapshot().rebuildQuietPeriod()).isEqualTo(Duration.ofMinutes(30));
        assertThat(settings.snapshot().rebuildMaxDelay()).isEqualTo(Duration.ofHours(6));

        env.setProperty("kwiki.graph.rebuild.quiet-period", "0m");
        assertThat(settings.reload(env)).isFalse();
        assertThat(settings.snapshot().rebuildQuietPeriod()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void 无法解析的值整组拒绝() {
        MockEnvironment env = new MockEnvironment();
        GraphRuntimeSettings settings = settings(env);
        env.setProperty("kwiki.graph.rebuild.enabled", "not-a-boolean");
        assertThat(settings.reload(env)).isFalse();
        assertThat(settings.snapshot().rebuildEnabled()).isTrue();
    }

    @Test
    void 删除的键回落默认值() {
        MockEnvironment withKeys = new MockEnvironment()
                .withProperty("kwiki.graph.rebuild.quiet-period", "10m")
                .withProperty("kwiki.graph.rebuild.auto-publish", "false");
        GraphRuntimeSettings settings = settings(withKeys);
        assertThat(settings.snapshot().rebuildQuietPeriod()).isEqualTo(Duration.ofMinutes(10));
        assertThat(settings.snapshot().rebuildAutoPublish()).isFalse();

        // Nacos 删除键后，刷新时环境中不再有该属性 → 回落默认值
        MockEnvironment cleared = new MockEnvironment()
                .withProperty("kwiki.graph.auto-publish", "false");
        assertThat(settings.reload(cleared)).isTrue();
        assertThat(settings.snapshot().rebuildQuietPeriod()).isEqualTo(Duration.ofMinutes(30));
        assertThat(settings.snapshot().rebuildAutoPublish()).isTrue();
    }

    @Test
    void 值未变化时不替换() {
        MockEnvironment env = new MockEnvironment();
        GraphRuntimeSettings settings = settings(env);
        assertThat(settings.reload(env)).isFalse();
    }
}
