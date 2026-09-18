package com.kwiki.indexing.version;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@ConditionalOnBean(JdbcTemplate.class)
class JpaRebuildRunRegistry implements RebuildRunRegistry {

    private final SearchIndexRebuildRunRepository runs;
    private final SearchIndexRebuildRangeRepository ranges;
    private final SearchIndexVersionRepository versions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    JpaRebuildRunRegistry(SearchIndexRebuildRunRepository runs,
                          SearchIndexRebuildRangeRepository ranges,
                          SearchIndexVersionRepository versions, JdbcTemplate jdbc,
                          ObjectMapper objectMapper) {
        this(runs, ranges, versions, jdbc, objectMapper, Clock.systemUTC());
    }

    JpaRebuildRunRegistry(SearchIndexRebuildRunRepository runs,
                          SearchIndexRebuildRangeRepository ranges,
                          SearchIndexVersionRepository versions, JdbcTemplate jdbc,
                          ObjectMapper objectMapper, Clock clock) {
        this.runs = runs;
        this.ranges = ranges;
        this.versions = versions;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Claim claimOrCreate(VersionRebuildCoordinator.Request request, String owner,
                               Duration leaseDuration) {
        Instant now = clock.instant();
        var active = runs.findFirstByVersionNumberAndStateInOrderByIdDesc(
                request.versionNumber(), List.of("PENDING", "RUNNING", "PAUSED"));
        if (active.isPresent()) {
            SearchIndexRebuildRun existing = active.get();
            if (existing.leaseActiveAt(now)) {
                return Claim.busy(existing);
            }
            existing.claim(owner, leaseDuration, now);
            return Claim.accepted(runs.save(existing), true);
        }

        SearchIndexVersion version = versions.findByVersionNumberForUpdate(request.versionNumber())
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown index version: " + request.versionNumber()));
        IndexVersionSnapshot building = IndexVersionStatusPolicy.startBuild(
                version.toSnapshot(false, false));
        BuildManifestSnapshot manifest = version.buildManifestSnapshot();
        long eventId = scalar("SELECT COALESCE(MAX(id), 0) FROM search_index_change_event");
        long generation = runs.findMaxBuildGeneration(request.versionNumber()) + 1;
        SearchIndexRebuildRun created = SearchIndexRebuildRun.create(
                request.versionNumber(), generation, request.kind(),
                manifest.configRevision(), json(manifest), request.requestedBy(),
                owner, leaseDuration, eventId, now);
        created = runs.saveAndFlush(created);
        ranges.save(new SearchIndexRebuildRange(created.getId(), "PAGE",
                pageBound("MIN"), pageBound("MAX")));
        ranges.save(new SearchIndexRebuildRange(created.getId(), "ATTACHMENT",
                attachmentBound("MIN"), attachmentBound("MAX")));
        version.applySnapshot(building);
        return Claim.accepted(created, false);
    }

    @Override
    @Transactional
    public void complete(long runId, String owner) {
        SearchIndexRebuildRun run = runs.findByIdAndLeaseOwner(runId, owner)
                .orElseThrow(() -> new IllegalStateException(
                        "rebuild run lease ownership changed: " + runId));
        run.complete(clock.instant());
        versions.findByVersionNumberForUpdate(run.getVersionNumber()).ifPresent(version ->
                version.applySnapshot(IndexVersionStatusPolicy.completeBuild(
                        version.toSnapshot(true, false), run.getConfigRevision())));
    }

    @Override
    @Transactional
    public void fail(long runId, String owner, Throwable failure) {
        runs.findByIdAndLeaseOwner(runId, owner).ifPresent(run -> {
            run.fail(failure, clock.instant());
            versions.findByVersionNumberForUpdate(run.getVersionNumber()).ifPresent(version ->
                    version.applySnapshot(IndexVersionStatusPolicy.failBuild(
                            version.toSnapshot(true, false))));
        });
    }

    @Override
    @Transactional
    public void cancel(long runId, String owner) {
        runs.findByIdAndLeaseOwner(runId, owner).ifPresent(run -> {
            run.cancel(clock.instant());
            versions.findByVersionNumberForUpdate(run.getVersionNumber()).ifPresent(version ->
                    version.applySnapshot(IndexVersionStatusPolicy.failBuild(
                            version.toSnapshot(true, false))));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> activeRunId(int versionNumber) {
        return runs.findFirstByVersionNumberAndStateInOrderByIdDesc(
                        versionNumber, List.of("PENDING", "RUNNING", "PAUSED"))
                .map(SearchIndexRebuildRun::getId);
    }

    private long pageBound(String aggregate) {
        return scalar("SELECT COALESCE(" + aggregate + "(p.id), 0) FROM wiki_page p "
                + "JOIN knowledge_base k ON k.id=p.kb_id WHERE p.node_type='PAGE' "
                + "AND p.status='ACTIVE' AND p.current_published_revision_id IS NOT NULL "
                + "AND k.status='ACTIVE'");
    }

    private long attachmentBound(String aggregate) {
        return scalar("SELECT COALESCE(" + aggregate + "(a.id), 0) FROM attachment a "
                + "JOIN knowledge_base k ON k.id=a.kb_id WHERE a.status='STORED' "
                + "AND k.status='ACTIVE' AND LOWER(a.content_type) IN "
                + "('image/png','image/jpeg','image/gif','image/webp')");
    }

    private long scalar(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private String json(BuildManifestSnapshot manifest) {
        try {
            return objectMapper.writeValueAsString(manifest);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("cannot serialize rebuild manifest", failure);
        }
    }
}
