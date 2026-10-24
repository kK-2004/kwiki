package com.kwiki.graph.persistence;

import com.kwiki.graph.config.GraphRuntimeSettings;
import com.kwiki.indexing.version.SearchIndexVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 每日调度的批次来源：以当前全局已发布的 Chunk 版本为目标，覆盖全部活跃知识库（ALL 范围）。
 * 参数推导与后台手动提交共用 {@link GraphBuildTargets}。幂等键按日期生成，同一天重复触发由
 * 批次服务回放而不会重复建批次。条件不满足（无已发布版本、版本不支持实体映射 schema v3、
 * 没有活跃知识库）时跳过当天并告警。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class CurrentChunkScheduledBatchFactory implements GraphScheduledBatchFactory {

    private static final Logger log = LoggerFactory.getLogger(CurrentChunkScheduledBatchFactory.class);
    private static final String REQUESTED_BY = "graph-scheduler";

    private final GraphBuildTargets targets;
    /** 与后台手动提交一致：autoPublish 每次提交时读取当前热更新快照，不缓存字段。 */
    private final GraphRuntimeSettings settings;

    @org.springframework.beans.factory.annotation.Autowired
    public CurrentChunkScheduledBatchFactory(GraphBuildTargets targets, GraphRuntimeSettings settings) {
        this.targets = targets;
        this.settings = settings;
    }

    @Override
    public Optional<GraphBuildBatchRequest> create(LocalDate scheduleDate) {
        SearchIndexVersion selected = targets.selectedVersion().orElse(null);
        if (selected == null) {
            log.warn("图谱每日构建跳过 {}：尚无已发布的全局 Chunk 索引版本", scheduleDate);
            return Optional.empty();
        }
        try {
            return Optional.of(targets.request("GRAPH_SCHEDULE:" + scheduleDate, GraphBuildTargets.SCOPE_ALL,
                    List.of(), selected, null, settings.snapshot().autoPublish(),
                    REQUESTED_BY, scheduleDate));
        } catch (IllegalArgumentException skipped) {
            log.warn("图谱每日构建跳过 {}：{}", scheduleDate, skipped.getMessage());
            return Optional.empty();
        }
    }
}
