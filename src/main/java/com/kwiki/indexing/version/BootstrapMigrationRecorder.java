package com.kwiki.indexing.version;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 首次部署的空库基线：为启动时直接建立的首个版本写一条「已完成、双写起点为 0」的存量迁移记录。
 * 全新部署时库中没有任何内容，事件水位 0 之前没有历史数据，这条记录如实描述了索引状态，
 * 使首个版本与后续版本一样能通过校验、被切走后也能直接切回。
 * 只用于全新部署；采纳已有别名（库与索引内容来源未知）时不得调用。
 */
@Service
public class BootstrapMigrationRecorder {

    static final String REQUESTED_BY = "system-bootstrap";

    private final SearchIndexRebuildRunRepository runs;
    private final ObjectMapper objectMapper;

    public BootstrapMigrationRecorder(SearchIndexRebuildRunRepository runs, ObjectMapper objectMapper) {
        this.runs = runs;
        this.objectMapper = objectMapper;
    }

    public SearchIndexRebuildRun recordEmptyBaseline(SearchIndexVersion version) {
        Long writeSession = version.getWriteEnabledEventId();
        if (!version.isWriteEnabled() || writeSession == null) {
            throw new IllegalStateException("bootstrap baseline requires a write-enabled version");
        }
        Instant now = Instant.now();
        SearchIndexRebuildRun run = SearchIndexRebuildRun.create(version.getVersionNumber(), 1,
                RebuildRunKind.MIGRATION, version.getConfigRevision(),
                json(version.buildManifestSnapshot()), REQUESTED_BY, REQUESTED_BY,
                Duration.ofSeconds(1), writeSession, now);
        run.complete(now);
        return runs.save(run);
    }

    private String json(BuildManifestSnapshot manifest) {
        try {
            return objectMapper.writeValueAsString(manifest);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("cannot serialize bootstrap manifest", failure);
        }
    }
}
