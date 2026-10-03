package com.kwiki.wiki.api;

import com.kwiki.graph.config.GraphPluginDefaults;
import com.kwiki.graph.plugin.CommunityDetectorRegistry;
import com.kwiki.graph.plugin.CommunityDetectorPlugin;
import com.kwiki.graph.plugin.CommunitySummarizerRegistry;
import com.kwiki.graph.plugin.GraphExtractorRegistry;
import com.kwiki.graph.plugin.GraphExtractorPlugin;
import com.kwiki.indexing.plugin.PluginAvailability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 插件列表接口：三类插件与默认值的目录输出。 */
class GraphPluginControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void 返回三类插件与默认值_含不可用原因() {
        GraphExtractorPlugin extractor = stubExtractor("kwiki-extractor-1", PluginAvailability.ok());
        CommunityDetectorPlugin detector = stubDetector("arcadedb-leiden-1",
                PluginAvailability.unavailable("ArcadeDB 未配置"));
        GraphPluginController controller = new GraphPluginController(
                new GraphExtractorRegistry(List.of(extractor)),
                new CommunityDetectorRegistry(List.of(detector)),
                new CommunitySummarizerRegistry(List.of()),
                new GraphPluginDefaults("kwiki-extractor-1", "arcadedb-leiden-1",
                        "kwiki-summarizer-1"));

        Map<String, Object> data = controller.plugins().getData();

        List<Map<String, Object>> extractors = (List<Map<String, Object>>) data.get("extractors");
        assertThat(extractors).hasSize(1);
        assertThat(extractors.get(0)).containsEntry("id", "kwiki-extractor-1")
                .containsEntry("available", true).containsEntry("unavailableReason", null);

        List<Map<String, Object>> detectors = (List<Map<String, Object>>) data.get("communityDetectors");
        assertThat(detectors.get(0)).containsEntry("available", false)
                .containsEntry("unavailableReason", "ArcadeDB 未配置");

        Map<String, Object> defaults = (Map<String, Object>) data.get("defaults");
        assertThat(defaults).containsEntry("extractor", "kwiki-extractor-1")
                .containsEntry("communityDetector", "arcadedb-leiden-1")
                .containsEntry("summarizer", "kwiki-summarizer-1");
    }

    private static GraphExtractorPlugin stubExtractor(String id, PluginAvailability availability) {
        return new GraphExtractorPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return "大模型抽取 v1";
            }

            @Override
            public String promptVersion() {
                return "p";
            }

            @Override
            public com.kwiki.graph.GraphExtractionResult extract(
                    com.kwiki.graph.GraphExtractionInput input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PluginAvailability availability() {
                return availability;
            }
        };
    }

    private static CommunityDetectorPlugin stubDetector(String id, PluginAvailability availability) {
        return new CommunityDetectorPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return "ArcadeDB Leiden v1";
            }

            @Override
            public com.kwiki.graph.CommunityDetectionResult detect(
                    com.kwiki.graph.CommunityDetectionInput input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PluginAvailability availability() {
                return availability;
            }
        };
    }
}
