package com.kwiki.indexing.gray;

import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.indexing.job.IndexingWorker;
import com.kwiki.wiki.api.ConflictException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 当前部署可用的解析器目录。新增解析器时在此登记（二期改为注册表）。 */
@Component
public class ParserCatalog {

    private static final Map<String, String> LABELS = Map.of(
            IndexingWorker.PARSER_VERSION, "tika-v1",
            IndexingWorker.PARSER_VERSION_MULTIMODAL, "pdfbox-v2");

    private final MultimodalSwitchReadiness readiness;

    public ParserCatalog(MultimodalSwitchReadiness readiness) {
        this.readiness = readiness;
    }

    public record ParserOption(String id, String label, boolean available, String unavailableReason) { }

    public static String label(String parserVersion) {
        return LABELS.getOrDefault(parserVersion, parserVersion);
    }

    public List<ParserOption> options() {
        boolean multimodalReady = readiness.ready();
        return List.of(
                new ParserOption(IndexingWorker.PARSER_VERSION, label(IndexingWorker.PARSER_VERSION), true, null),
                new ParserOption(IndexingWorker.PARSER_VERSION_MULTIMODAL,
                        label(IndexingWorker.PARSER_VERSION_MULTIMODAL), multimodalReady,
                        multimodalReady ? null : "缺少配置：" + String.join("、", readiness.missingConfiguration())));
    }

    public void requireAvailable(String parserVersion) {
        ParserOption option = options().stream().filter(item -> item.id().equals(parserVersion)).findFirst()
                .orElseThrow(() -> new ConflictException("未知的解析器：" + parserVersion));
        if (!option.available()) {
            throw new ConflictException("解析器 " + option.label() + " 暂不可用，" + option.unavailableReason());
        }
    }
}
