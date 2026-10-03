package com.kwiki.graph.plugin;

import com.kwiki.graph.CommunityDetectionInput;
import com.kwiki.graph.CommunityDetectionResult;
import com.kwiki.graph.CommunitySummary;
import com.kwiki.graph.CommunitySummaryInput;
import com.kwiki.graph.GraphExtractionInput;
import com.kwiki.graph.GraphExtractionResult;
import com.kwiki.graph.config.GraphPluginDefaults;
import com.kwiki.indexing.plugin.PluginAvailability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 图构建插件注册表与默认值校验。 */
class GraphPluginRegistryAndDefaultsTest {

    private static GraphExtractorPlugin extractor(String id) {
        return new GraphExtractorPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return id;
            }

            @Override
            public String promptVersion() {
                return "p";
            }

            @Override
            public GraphExtractionResult extract(GraphExtractionInput input) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static CommunityDetectorPlugin detector(String id) {
        return new CommunityDetectorPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return id;
            }

            @Override
            public CommunityDetectionResult detect(CommunityDetectionInput input) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static CommunitySummarizerPlugin summarizer(String id) {
        return new CommunitySummarizerPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return id;
            }

            @Override
            public String promptVersion() {
                return "p";
            }

            @Override
            public String modelName() {
                return "m";
            }

            @Override
            public CommunitySummary summarize(CommunitySummaryInput input) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void 注册表按id解析并提供全部插件() {
        GraphExtractorRegistry extractors = new GraphExtractorRegistry(
                List.of(extractor(LlmGraphExtractorV1.ID)));
        CommunityDetectorRegistry detectors = new CommunityDetectorRegistry(
                List.of(detector(ArcadeDbLeidenV1.ID)));
        CommunitySummarizerRegistry summarizers = new CommunitySummarizerRegistry(
                List.of(summarizer(LlmCommunitySummarizerV1.ID)));

        assertThat(extractors.find("kwiki-extractor-1")).isPresent();
        assertThat(extractors.find("missing")).isEmpty();
        assertThat(extractors.require("kwiki-extractor-1").id()).isEqualTo("kwiki-extractor-1");
        assertThat(extractors.all()).hasSize(1);
        assertThatThrownBy(() -> extractors.require("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("抽取器未注册");
    }

    @Test
    void 默认三项都已注册时校验通过() {
        GraphExtractorRegistry extractors = new GraphExtractorRegistry(
                List.of(extractor("kwiki-extractor-1")));
        CommunityDetectorRegistry detectors = new CommunityDetectorRegistry(
                List.of(detector("arcadedb-leiden-1")));
        CommunitySummarizerRegistry summarizers = new CommunitySummarizerRegistry(
                List.of(summarizer("kwiki-summarizer-1")));

        assertThatCode(() -> new GraphPluginDefaultsValidator(defaults(), extractors,
                detectors, summarizers)).doesNotThrowAnyException();
    }

    @Test
    void 任一默认插件未注册时启动失败() {
        GraphExtractorRegistry extractors = new GraphExtractorRegistry(
                List.of(extractor("kwiki-extractor-1")));
        CommunityDetectorRegistry detectors = new CommunityDetectorRegistry(
                List.of(detector("arcadedb-leiden-1")));
        CommunitySummarizerRegistry summarizers = new CommunitySummarizerRegistry(
                List.of(summarizer("other-summarizer")));

        assertThatThrownBy(() -> new GraphPluginDefaultsValidator(defaults(), extractors,
                detectors, summarizers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("默认摘要器未注册");
    }

    @Test
    void 插件默认值使用三个首个实现id() {
        GraphPluginDefaults defaults = defaults();

        assertThat(defaults.extractor()).isEqualTo("kwiki-extractor-1");
        assertThat(defaults.communityDetector()).isEqualTo("arcadedb-leiden-1");
        assertThat(defaults.summarizer()).isEqualTo("kwiki-summarizer-1");
    }

    private static GraphPluginDefaults defaults() {
        return new GraphPluginDefaults("kwiki-extractor-1", "arcadedb-leiden-1",
                "kwiki-summarizer-1");
    }

    /** 编译期守住插件契约：available 为默认方法，不应被实现覆盖为不可用。 */
    @Test
    void 匿名实现的可用性默认为可用() {
        assertThat(extractor("x").availability()).isEqualTo(PluginAvailability.ok());
        assertThat(detector("x").availability()).isEqualTo(PluginAvailability.ok());
        assertThat(summarizer("x").availability()).isEqualTo(PluginAvailability.ok());
    }
}
