package com.kwiki.graph.plugin;

import com.kwiki.graph.config.GraphPluginDefaults;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 启动时确认三项默认图构建插件已注册，防止错误配置进入运行阶段。 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphPluginDefaultsValidator {

    public GraphPluginDefaultsValidator(GraphPluginDefaults defaults,
                                        GraphExtractorRegistry extractors,
                                        CommunityDetectorRegistry detectors,
                                        CommunitySummarizerRegistry summarizers) {
        if (extractors.find(defaults.extractor()).isEmpty()) {
            throw new IllegalStateException("默认抽取器未注册：" + defaults.extractor());
        }
        if (detectors.find(defaults.communityDetector()).isEmpty()) {
            throw new IllegalStateException("默认社区检测器未注册：" + defaults.communityDetector());
        }
        if (summarizers.find(defaults.summarizer()).isEmpty()) {
            throw new IllegalStateException("默认摘要器未注册：" + defaults.summarizer());
        }
    }
}
