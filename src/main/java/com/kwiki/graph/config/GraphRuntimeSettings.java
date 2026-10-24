package com.kwiki.graph.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 图发布与去抖重建的运行时设置，支持配置中心热更新：
 * {@code kwiki.graph.auto-publish}（键名与默认值沿用原 GraphProperties.autoPublish）
 * 与 {@code kwiki.graph.rebuild.*} 四项。默认值写在代码常量里，不依赖
 * application.yml；时长同时接受 {@code 30m} 简写与 ISO-8601 两种写法。
 *
 * <p>热更新语义与 {@code QaRetrievalBudgets} 一致：Nacos 推送后（{@link
 * EnvironmentChangeEvent}）整组重新读取并校验（quiet-period &gt; 0、
 * max-delay ≥ quiet-period），通过则原子替换整份快照并记录变更前后值；
 * 不通过则保留原值并记 WARN；被删除的键回落默认值。启动时配置非法直接失败。
 * 使用方每次提交/调度取一次 {@link #snapshot()}，同一次操作不混用两个版本；
 * 配置变更只影响之后提交的批次，已提交批次沿用提交时写入的 auto_publish 列。
 */
@Component
public class GraphRuntimeSettings implements EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(GraphRuntimeSettings.class);

    private static final String PREFIX = "kwiki.graph.";
    static final boolean DEFAULT_AUTO_PUBLISH = false;
    static final boolean DEFAULT_REBUILD_ENABLED = true;
    static final Duration DEFAULT_REBUILD_QUIET_PERIOD = Duration.ofMinutes(30);
    static final Duration DEFAULT_REBUILD_MAX_DELAY = Duration.ofHours(6);
    static final boolean DEFAULT_REBUILD_AUTO_PUBLISH = true;

    /** 一份经校验的完整设置；热更新时整体替换。 */
    public record Snapshot(boolean autoPublish, boolean rebuildEnabled,
                           Duration rebuildQuietPeriod,
                           Duration rebuildMaxDelay,
                           boolean rebuildAutoPublish) {
        public Snapshot {
            if (rebuildQuietPeriod == null || rebuildQuietPeriod.isZero()
                    || rebuildQuietPeriod.isNegative()) {
                throw new IllegalArgumentException("kwiki.graph.rebuild.quiet-period 必须大于 0");
            }
            if (rebuildMaxDelay == null || rebuildMaxDelay.compareTo(rebuildQuietPeriod) < 0) {
                throw new IllegalArgumentException("kwiki.graph.rebuild.max-delay 不能小于 quiet-period");
            }
        }
    }

    private volatile Snapshot snapshot;
    private Environment environment;

    public GraphRuntimeSettings(
            @Value("${" + PREFIX + "auto-publish:" + DEFAULT_AUTO_PUBLISH + "}") boolean autoPublish,
            @Value("${" + PREFIX + "rebuild.enabled:" + DEFAULT_REBUILD_ENABLED + "}") boolean rebuildEnabled,
            @Value("${" + PREFIX + "rebuild.quiet-period:30m}") String rebuildQuietPeriod,
            @Value("${" + PREFIX + "rebuild.max-delay:6h}") String rebuildMaxDelay,
            @Value("${" + PREFIX + "rebuild.auto-publish:" + DEFAULT_REBUILD_AUTO_PUBLISH + "}")
            boolean rebuildAutoPublish) {
        // 启动时配置非法直接失败，与热更新时保留旧值不同
        this.snapshot = new Snapshot(autoPublish, rebuildEnabled,
                parse("rebuild.quiet-period", rebuildQuietPeriod, DEFAULT_REBUILD_QUIET_PERIOD),
                parse("rebuild.max-delay", rebuildMaxDelay, DEFAULT_REBUILD_MAX_DELAY),
                rebuildAutoPublish);
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /** 配置中心推送后重新读取；变更键不一定是规范写法，故不按键过滤，值未变则不做任何事。 */
    @EventListener(EnvironmentChangeEvent.class)
    public void onEnvironmentChange() {
        if (environment != null) {
            reload(environment);
        }
    }

    /** 按当前环境重新读取并校验；返回是否发生替换。 */
    boolean reload(Environment env) {
        Snapshot current = snapshot;
        Snapshot next;
        try {
            next = new Snapshot(
                    env.getProperty(PREFIX + "auto-publish", Boolean.class, DEFAULT_AUTO_PUBLISH),
                    env.getProperty(PREFIX + "rebuild.enabled", Boolean.class, DEFAULT_REBUILD_ENABLED),
                    readDuration(env, "rebuild.quiet-period", DEFAULT_REBUILD_QUIET_PERIOD),
                    readDuration(env, "rebuild.max-delay", DEFAULT_REBUILD_MAX_DELAY),
                    env.getProperty(PREFIX + "rebuild.auto-publish", Boolean.class,
                            DEFAULT_REBUILD_AUTO_PUBLISH));
        } catch (RuntimeException e) {
            // 含无法解析的时长/布尔值（ConversionFailedException 等）与校验失败
            log.warn("graph runtime settings refresh rejected, keeping {}: {}", current, e.getMessage());
            return false;
        }
        if (next.equals(current)) {
            return false;
        }
        snapshot = next;
        log.info("graph runtime settings refreshed: {} -> {}", current, next);
        return true;
    }

    private static Duration readDuration(Environment env, String key, Duration defaultValue) {
        String raw = env.getProperty(PREFIX + key);
        return raw == null || raw.isBlank() ? defaultValue : DurationStyle.detectAndParse(raw.trim());
    }

    private static Duration parse(String key, String raw, Duration defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return DurationStyle.detectAndParse(raw.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("kwiki.graph." + key + " 无法解析为时长: " + raw, e);
        }
    }

    public Snapshot snapshot() {
        return snapshot;
    }
}
