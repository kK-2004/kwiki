package com.kwiki.graph.persistence;

import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 每日调度的批次来源：以当前全局已发布的 Chunk 版本为目标，覆盖全部活跃知识库（ALL 范围）。
 * 幂等键按日期生成，同一天重复触发由批次服务回放而不会重复建批次。
 * 条件不满足（无已发布版本、版本不支持实体映射 schema v3、没有活跃知识库）时跳过当天并告警。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class CurrentChunkScheduledBatchFactory implements GraphScheduledBatchFactory {

    private static final Logger log = LoggerFactory.getLogger(CurrentChunkScheduledBatchFactory.class);
    private static final String REQUESTED_BY = "graph-scheduler";
    /** 图构建依赖实体映射字段，仅 schema v3 及以上的 Chunk 版本可作为目标。 */
    private static final int MIN_MAPPING_SCHEMA_VERSION = 3;

    private final SearchIndexVersionRepository versions;
    private final JdbcTemplate jdbc;
    /** 与后台手动提交一致：是否自动发布跟随 kwiki.graph.auto-publish。 */
    private final boolean autoPublish;

    CurrentChunkScheduledBatchFactory(SearchIndexVersionRepository versions, JdbcTemplate jdbc) {
        this(versions, jdbc, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CurrentChunkScheduledBatchFactory(SearchIndexVersionRepository versions, JdbcTemplate jdbc,
                                             com.kwiki.graph.config.GraphProperties graph) {
        this(versions, jdbc, graph.autoPublish());
    }

    private CurrentChunkScheduledBatchFactory(SearchIndexVersionRepository versions, JdbcTemplate jdbc,
                                              boolean autoPublish) {
        this.versions = versions;
        this.jdbc = jdbc;
        this.autoPublish = autoPublish;
    }

    @Override
    public Optional<GraphBuildBatchRequest> create(LocalDate scheduleDate) {
        SearchIndexVersion selected = versions.findBySelectedTrue().orElse(null);
        if (selected == null) {
            log.warn("图谱每日构建跳过 {}：尚无已发布的全局 Chunk 索引版本", scheduleDate);
            return Optional.empty();
        }
        EditableIndexConfig config = selected.editableConfig();
        if (config.mappingSchemaVersion() < MIN_MAPPING_SCHEMA_VERSION) {
            log.warn("图谱每日构建跳过 {}：全局版本 {} 的结构版本为 {}，图构建需要 schema v{} 及以上",
                    scheduleDate, selected.getPhysicalName(), config.mappingSchemaVersion(),
                    MIN_MAPPING_SCHEMA_VERSION);
            return Optional.empty();
        }
        List<Long> knowledgeBaseIds = jdbc.queryForList(
                "SELECT id FROM knowledge_base WHERE status = 'ACTIVE' ORDER BY id", Long.class);
        if (knowledgeBaseIds.isEmpty()) {
            log.info("图谱每日构建跳过 {}：没有活跃的知识库", scheduleDate);
            return Optional.empty();
        }
        return Optional.of(new GraphBuildBatchRequest(
                "GRAPH_SCHEDULE:" + scheduleDate,
                "ALL",
                knowledgeBaseIds,
                selected.getVersionNumber(),
                selected.getPhysicalName(),
                config.mappingSchemaVersion(),
                selected.getConfigRevision(),
                VersionedIndexingPipelineRegistry.ENTITY_LINKING_VERSION,
                autoPublish,
                REQUESTED_BY,
                scheduleDate));
    }
}
