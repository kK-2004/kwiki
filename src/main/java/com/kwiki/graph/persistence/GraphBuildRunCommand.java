package com.kwiki.graph.persistence;

/** 创建按知识库隔离的图构建子任务时冻结的版本与身份。 */
public record GraphBuildRunCommand(
        long batchId,
        long kbId,
        int chunkIndexVersion,
        String chunkPhysicalIndex,
        long communityIndexVersion,
        String communityPhysicalIndex,
        long graphVersion,
        int mappingSchemaVersion,
        String entityLinkingVersion,
        long contentEpoch,
        long securityEpoch) {

    public GraphBuildRunCommand {
        if (batchId <= 0 || kbId <= 0 || chunkIndexVersion < 1
                || communityIndexVersion < 1 || graphVersion < 1
                || mappingSchemaVersion < 1 || contentEpoch < 0 || securityEpoch < 0) {
            throw new IllegalArgumentException("图构建子任务身份无效");
        }
        require(chunkPhysicalIndex, "chunkPhysicalIndex");
        require(communityPhysicalIndex, "communityPhysicalIndex");
        require(entityLinkingVersion, "entityLinkingVersion");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
