package com.kwiki.indexing.version;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/** 建立切换补齐边界；不修改读别名，也不在此处执行实际补齐。 */
@Service
public class SwitchPreparationService {

    private final SearchIndexVersionRepository versions;
    private final SearchIndexRebuildRunRepository runs;
    private final SearchIndexRebuildRangeRepository ranges;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public SwitchPreparationService(SearchIndexVersionRepository versions,
                                    SearchIndexRebuildRunRepository runs,
                                    SearchIndexRebuildRangeRepository ranges,
                                    JdbcTemplate jdbc) {
        this(versions, runs, ranges, jdbc, Clock.systemUTC());
    }

    SwitchPreparationService(SearchIndexVersionRepository versions,
                             SearchIndexRebuildRunRepository runs,
                             SearchIndexRebuildRangeRepository ranges,
                             JdbcTemplate jdbc, Clock clock) {
        this.versions = versions;
        this.runs = runs;
        this.ranges = ranges;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 锁定全部版本行，使实时入队要么完整落在旧目标集合及水位之前，
     * 要么在提交后看到全新的多写集合，避免边界事件丢失。
     */
    @Transactional
    public Preparation prepare(int targetVersion) {
        List<SearchIndexVersion> activeVersions = versions.findAllActiveForUpdate();
        SearchIndexVersion target = activeVersions.stream()
                .filter(version -> version.getVersionNumber() == targetVersion)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown active index version: " + targetVersion));
        requireBuiltTarget(target);

        SearchIndexRebuildRun run = runs
                .findFirstByVersionNumberAndConfigRevisionAndStateOrderByIdDesc(
                        targetVersion, target.getConfigRevision(), RebuildRunState.COMPLETED.name())
                .orElseThrow(() -> new IllegalStateException(
                        "version " + targetVersion + " has no completed rebuild"));
        List<SearchIndexRebuildRange> runRanges = ranges
                .findByRunIdOrderByResourceType(run.getId());
        if (runRanges.isEmpty()) {
            throw new IllegalStateException("completed rebuild has no captured ranges");
        }

        activeVersions.stream()
                .filter(version -> !version.isAdminDisabled())
                .filter(SearchIndexVersion::isPipelineSupported)
                .forEach(SearchIndexVersion::enableForSwitchPreparation);
        versions.flush();

        long eventId = scalar("SELECT COALESCE(MAX(id), 0) FROM search_index_change_event");
        for (SearchIndexRebuildRange range : runRanges) {
            range.captureTailUpperBound(currentUpperBound(range.getResourceType()));
        }
        run.startSwitchPreparation(eventId, clock.instant());
        return new Preparation(run.getId(), eventId,
                runRanges.stream().map(range -> new TailRange(range.getResourceType(),
                        range.getTailLastSeenId(), range.getTailMaxId())).toList());
    }

    private static void requireBuiltTarget(SearchIndexVersion target) {
        if (target.isAdminDisabled() || !target.isPipelineSupported()
                || !IndexBuildState.BUILT.name().equals(target.getBuildState())
                || target.getBuiltConfigRevision() == null
                || target.getBuiltConfigRevision() != target.getConfigRevision()
                || target.getNeedsAttentionReason() != null) {
            throw new IllegalStateException(
                    "version " + target.getVersionNumber() + " is not a clean supported build");
        }
    }

    private long currentUpperBound(String resourceType) {
        return switch (resourceType) {
            case "PAGE" -> scalar("SELECT COALESCE(MAX(p.id), 0) FROM wiki_page p "
                    + "JOIN knowledge_base k ON k.id=p.kb_id WHERE p.node_type='PAGE' "
                    + "AND p.status='ACTIVE' AND p.current_published_revision_id IS NOT NULL "
                    + "AND k.status='ACTIVE'");
            case "ATTACHMENT" -> scalar("SELECT COALESCE(MAX(a.id), 0) FROM attachment a "
                    + "JOIN knowledge_base k ON k.id=a.kb_id WHERE a.status='STORED' "
                    + "AND k.status='ACTIVE' AND LOWER(a.content_type) IN "
                    + "('image/png','image/jpeg','image/gif','image/webp')");
            default -> throw new IllegalStateException(
                    "unsupported rebuild resource type: " + resourceType);
        };
    }

    private long scalar(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    public record Preparation(long runId, long dualWriteStartEventId,
                              List<TailRange> ranges) { }

    public record TailRange(String resourceType, long lastSeenId, long upperBound) { }
}
