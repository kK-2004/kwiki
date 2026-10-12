package com.kwiki.indexing.config;

import com.kwiki.infrastructure.ai.registry.ModelPurpose;
import com.kwiki.infrastructure.ai.registry.ModelRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** parse-2 插件使用的多模态部署就绪检查；不返回凭据值。 */
@Component
public class MultimodalSwitchReadiness {
    private final MultimodalIndexingProperties multimodal;
    private final ModelRegistry models;

    public MultimodalSwitchReadiness(MultimodalIndexingProperties multimodal, ModelRegistry models) {
        this.multimodal = multimodal;
        this.models = models;
    }

    public List<String> missingConfiguration() {
        List<String> missing = new ArrayList<>();
        if (!Boolean.TRUE.equals(multimodal.enabled())) {
            missing.add("KWIKI_MULTIMODAL_ENABLED=true");
        }
        if (models.find(ModelPurpose.VISION_SUMMARY).isEmpty()) {
            missing.add("模型配置：" + ModelPurpose.VISION_SUMMARY.name() + "（" + ModelPurpose.VISION_SUMMARY.label() + "）");
        }
        return List.copyOf(missing);
    }

    public boolean ready() {
        return missingConfiguration().isEmpty();
    }

}
