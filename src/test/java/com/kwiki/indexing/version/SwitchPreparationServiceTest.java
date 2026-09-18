package com.kwiki.indexing.version;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SwitchPreparationServiceTest {

    private static final EditableIndexConfig CONFIG = new EditableIndexConfig(
            "parser-1", "chunker-1", "default", "embedding-1", 1024, 1);

    @Test
    void atomicallyEnablesEligibleVersionsAndCapturesWatermarksAndTailBounds() {
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
        SearchIndexRebuildRangeRepository ranges = mock(SearchIndexRebuildRangeRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SearchIndexVersion current = SearchIndexVersion.bootstrapped(
                1, "kwiki-chunks-v1", CONFIG, "mapping");
        SearchIndexVersion target = builtVersion(2);
        SearchIndexVersion disabled = builtVersion(3);
        disabled.disableByAdministrator();
        SearchIndexRebuildRun run = completedRun(2);
        SearchIndexRebuildRange pages = new SearchIndexRebuildRange(91, "PAGE", 2, 10);
        SearchIndexRebuildRange attachments =
                new SearchIndexRebuildRange(91, "ATTACHMENT", 0, 0);

        when(versions.findAllActiveForUpdate()).thenReturn(List.of(current, target, disabled));
        when(runs.findFirstByVersionNumberAndConfigRevisionAndStateOrderByIdDesc(
                2, 1, "COMPLETED")).thenReturn(Optional.of(run));
        when(ranges.findByRunIdOrderByResourceType(91))
                .thenReturn(List.of(attachments, pages));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("change_event")) return 42L;
                    if (sql.contains("wiki_page")) return 15L;
                    if (sql.contains("attachment")) return 7L;
                    throw new AssertionError(sql);
                });

        SwitchPreparationService.Preparation result = service(versions, runs, ranges, jdbc)
                .prepare(2);

        assertThat(current.isWriteEnabled()).isTrue();
        assertThat(target.isWriteEnabled()).isTrue();
        assertThat(disabled.isWriteEnabled()).isFalse();
        assertThat(result.runId()).isEqualTo(91);
        assertThat(result.dualWriteStartEventId()).isEqualTo(42);
        assertThat(result.ranges()).containsExactly(
                new SwitchPreparationService.TailRange("ATTACHMENT", 0, 7),
                new SwitchPreparationService.TailRange("PAGE", 10, 15));
        verify(versions).flush();
        assertThat(run.getDualWriteStartEventId()).isEqualTo(42);
    }

    @Test
    void rejectsDirtyTargetBeforeChangingWriteTargets() {
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        SearchIndexVersion dirty = new SearchIndexVersion(
                2, "kwiki-chunks-v2", CONFIG, "mapping");
        when(versions.findAllActiveForUpdate()).thenReturn(List.of(dirty));

        assertThatThrownBy(() -> service(versions,
                mock(SearchIndexRebuildRunRepository.class),
                mock(SearchIndexRebuildRangeRepository.class), mock(JdbcTemplate.class))
                .prepare(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a clean supported build");
        assertThat(dirty.isWriteEnabled()).isFalse();
    }

    private static SwitchPreparationService service(SearchIndexVersionRepository versions,
                                                    SearchIndexRebuildRunRepository runs,
                                                    SearchIndexRebuildRangeRepository ranges,
                                                    JdbcTemplate jdbc) {
        return new SwitchPreparationService(versions, runs, ranges, jdbc,
                Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC));
    }

    private static SearchIndexVersion builtVersion(int versionNumber) {
        SearchIndexVersion version = new SearchIndexVersion(
                versionNumber, "kwiki-chunks-v" + versionNumber, CONFIG, "mapping");
        version.applySnapshot(IndexVersionStatusPolicy.completeBuild(
                version.toSnapshot(false, false), 1));
        return version;
    }

    private static SearchIndexRebuildRun completedRun(int versionNumber) {
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        SearchIndexRebuildRun run = SearchIndexRebuildRun.create(versionNumber, 1,
                RebuildRunKind.INITIAL, 1, "{}", "admin", "owner",
                java.time.Duration.ofMinutes(2), 12, now);
        run.complete(now);
        try {
            var id = SearchIndexRebuildRun.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(run, 91L);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        return run;
    }
}
