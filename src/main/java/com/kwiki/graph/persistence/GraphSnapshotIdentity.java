package com.kwiki.graph.persistence;

/**
 * 创建图快照时冻结的算法与插件身份；extractor / communityDetector /
 * summarizer 是产出该快照的插件组合，随快照持久化便于追溯。
 */
public record GraphSnapshotIdentity(String extractor,
                                    String communityDetector,
                                    String summarizer,
                                    String algorithmMode,
                                    String engineVersion,
                                    int graphSchemaVersion,
                                    String summaryModel,
                                    String summaryPromptVersion,
                                    String embeddingModel,
                                    Integer embeddingDimensions) {

    public GraphSnapshotIdentity {
        if (extractor == null || extractor.isBlank()
                || communityDetector == null || communityDetector.isBlank()
                || summarizer == null || summarizer.isBlank()
                || algorithmMode == null || algorithmMode.isBlank()
                || engineVersion == null || engineVersion.isBlank()
                || graphSchemaVersion < 1) {
            throw new IllegalArgumentException("图快照身份无效");
        }
    }
}
