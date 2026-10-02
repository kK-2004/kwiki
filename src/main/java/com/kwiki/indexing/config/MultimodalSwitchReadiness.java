package com.kwiki.indexing.config;

import com.kwiki.infrastructure.config.ExternalServicesProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** parse-2 插件使用的多模态部署就绪检查；不返回凭据值。 */
@Component
public class MultimodalSwitchReadiness {
    private final MultimodalIndexingProperties multimodal;
    private final ExternalServicesProperties external;

    public MultimodalSwitchReadiness(MultimodalIndexingProperties multimodal,
                                     ExternalServicesProperties external) {
        this.multimodal = multimodal;
        this.external = external;
    }

    public List<String> missingConfiguration() {
        List<String> missing = new ArrayList<>();
        if (!Boolean.TRUE.equals(multimodal.enabled())) {
            missing.add("KWIKI_MULTIMODAL_ENABLED=true");
        }
        ExternalServicesProperties.VisionModel vision = external.visionModel();
        if (vision == null || vision.baseUrl() == null || vision.baseUrl().isBlank()
                || !vision.baseUrl().matches("https?://\\S+")) {
            missing.add("KWIKI_VISION_BASE_URL");
        }
        if (vision == null || vision.apiKey() == null || vision.apiKey().isBlank()) {
            missing.add("KWIKI_VISION_API_KEY");
        }
        return List.copyOf(missing);
    }

    public boolean ready() {
        return missingConfiguration().isEmpty();
    }

}
