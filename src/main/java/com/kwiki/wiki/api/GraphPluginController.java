package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.graph.config.GraphPluginDefaults;
import com.kwiki.graph.plugin.CommunityDetectorRegistry;
import com.kwiki.graph.plugin.CommunitySummarizerRegistry;
import com.kwiki.graph.plugin.GraphExtractorRegistry;
import com.kwiki.indexing.plugin.IndexPlugin;
import com.kwiki.indexing.plugin.PluginRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 图构建插件目录：管理端提交批次前展示三类可选插件及其可用性。
 * 可用性原因不含凭据或内部地址细节。
 */
@RestController
@RequestMapping("/api/v1/admin/knowledge-graphs")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphPluginController {

    private final GraphExtractorRegistry extractors;
    private final CommunityDetectorRegistry detectors;
    private final CommunitySummarizerRegistry summarizers;
    private final GraphPluginDefaults defaults;

    public GraphPluginController(GraphExtractorRegistry extractors,
                                 CommunityDetectorRegistry detectors,
                                 CommunitySummarizerRegistry summarizers,
                                 GraphPluginDefaults defaults) {
        this.extractors = extractors;
        this.detectors = detectors;
        this.summarizers = summarizers;
        this.defaults = defaults;
    }

    @GetMapping("/plugins")
    public TransDTO<Map<String, Object>> plugins() {
        return TransDTO.success(Map.of(
                "extractors", items(extractors),
                "communityDetectors", items(detectors),
                "summarizers", items(summarizers),
                "defaults", Map.of(
                        "extractor", defaults.extractor(),
                        "communityDetector", defaults.communityDetector(),
                        "summarizer", defaults.summarizer())));
    }

    private static List<Map<String, Object>> items(
            PluginRegistry<? extends IndexPlugin> registry) {
        return registry.all().stream().map(plugin -> {
            var availability = plugin.availability();
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", plugin.id());
            item.put("label", plugin.label());
            item.put("available", availability.available());
            item.put("unavailableReason", availability.unavailableReason());
            return item;
        }).toList();
    }
}
