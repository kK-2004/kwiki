package com.kwiki.graph.persistence;

import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 图构建批次的目标解析：调用方只给出范围与 Chunk 版本号，其余参数（物理索引名、结构版本、
 * 配置修订号、实体链接版本）一律由服务端按所选版本推导，避免前端拼写参数与索引实际不一致。
 * ALL 范围在服务端展开为全部活跃知识库。后台手动提交与每日调度共用本组件。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphBuildTargets {

    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_KNOWLEDGE_BASE = "KNOWLEDGE_BASE";
    /** 图构建依赖实体映射字段，仅 schema v3 及以上的 Chunk 版本可作为目标。 */
    static final int MIN_MAPPING_SCHEMA_VERSION = 3;

    private final SearchIndexVersionRepository versions;
    private final JdbcTemplate jdbc;

    public GraphBuildTargets(SearchIndexVersionRepository versions, JdbcTemplate jdbc) {
        this.versions = versions;
        this.jdbc = jdbc;
    }

    /** 当前全局已发布的 Chunk 版本。 */
    public Optional<SearchIndexVersion> selectedVersion() {
        return versions.findBySelectedTrue();
    }

    public List<Long> activeKnowledgeBaseIds() {
        return jdbc.queryForList("SELECT id FROM knowledge_base WHERE status = 'ACTIVE' ORDER BY id", Long.class);
    }

    /**
     * 按版本号解析目标并生成批次请求。
     *
     * @param requestedEntityLinkingVersion 调用方声明的实体链接版本；为空时取流水线当前版本，
     *                                      非空且不一致时拒绝（避免构建出无法与 Chunk 文档对齐的图）
     * @throws IllegalArgumentException 参数不合法，消息为可直接展示的中文
     */
    public GraphBuildBatchRequest request(String idempotencyKey, String scopeKind, List<Long> knowledgeBaseIds,
                                          int chunkIndexVersion, String requestedEntityLinkingVersion,
                                          boolean autoPublish, String requestedBy, LocalDate scheduleDate) {
        SearchIndexVersion target = versions.findByVersionNumber(chunkIndexVersion)
                .filter(version -> version.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("Chunk 版本 v" + chunkIndexVersion + " 不存在或已删除"));
        return request(idempotencyKey, scopeKind, knowledgeBaseIds, target, requestedEntityLinkingVersion,
                autoPublish, requestedBy, scheduleDate);
    }

    /** 以给定版本为目标生成批次请求；校验规则同上。 */
    public GraphBuildBatchRequest request(String idempotencyKey, String scopeKind, List<Long> knowledgeBaseIds,
                                          SearchIndexVersion target, String requestedEntityLinkingVersion,
                                          boolean autoPublish, String requestedBy, LocalDate scheduleDate) {
        EditableIndexConfig config = target.editableConfig();
        if (config.mappingSchemaVersion() < MIN_MAPPING_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Chunk 版本 " + target.getPhysicalName() + " 的结构版本为 v"
                    + config.mappingSchemaVersion() + "，图构建需要 v" + MIN_MAPPING_SCHEMA_VERSION + " 及以上");
        }
        String entityLinking = VersionedIndexingPipelineRegistry.ENTITY_LINKING_VERSION;
        if (requestedEntityLinkingVersion != null && !requestedEntityLinkingVersion.isBlank()
                && !entityLinking.equals(requestedEntityLinkingVersion.trim())) {
            throw new IllegalArgumentException("实体链接版本应为 " + entityLinking + "（与 Chunk 文档写入的版本一致），收到 "
                    + requestedEntityLinkingVersion.trim());
        }
        List<Long> scope = switch (scopeKind == null ? "" : scopeKind) {
            case SCOPE_ALL -> activeKnowledgeBaseIds();
            case SCOPE_KNOWLEDGE_BASE -> {
                if (knowledgeBaseIds == null || knowledgeBaseIds.size() != 1) {
                    throw new IllegalArgumentException("指定知识库范围必须且只能选择一个知识库");
                }
                yield knowledgeBaseIds;
            }
            default -> throw new IllegalArgumentException("未知的构建范围：" + scopeKind);
        };
        if (scope.isEmpty()) {
            throw new IllegalArgumentException("没有活跃的知识库可供构建");
        }
        return new GraphBuildBatchRequest(idempotencyKey, scopeKind, scope, target.getVersionNumber(),
                target.getPhysicalName(), config.mappingSchemaVersion(), target.getConfigRevision(),
                entityLinking, autoPublish, requestedBy, scheduleDate);
    }
}
